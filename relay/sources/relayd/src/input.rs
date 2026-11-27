// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

pub mod watch;

use std::{
    ffi::OsStr,
    io::{Cursor, Read},
    path::Path,
};

use anyhow::Error;
use flate2::read::GzDecoder;
use foreign_types::ForeignTypeRef;
use openssl::{
    nid::Nid,
    pkcs7::{Pkcs7, Pkcs7Flags, Pkcs7Ref, Pkcs7SignerInfo},
    stack::{Stack, StackRef},
    x509::{X509, X509AlgorithmRef, store::X509StoreBuilder},
};
use tokio::fs::read;
use tracing::debug;
use zip::read::ZipArchive;

use crate::{error::RudderError, hashing::HashType};

/// Reads a received report, decompressing it if needed.
///
/// `limit` is the maximum decompressed size in bytes: the content is not authenticated yet,
/// so this is what protects against decompression bombs.
pub async fn read_compressed_file<P: AsRef<Path>>(path: P, limit: u64) -> Result<Vec<u8>, Error> {
    let path = path.as_ref();

    debug!("Reading {:#?} content", path);
    let data = read(path).await?;
    decompress(path, data, limit)
}

/// Decompresses `data` according to the extension of `path`, refusing to produce
/// more than `limit` bytes.
fn decompress(path: &Path, data: Vec<u8>, limit: u64) -> Result<Vec<u8>, Error> {
    match path.extension().and_then(OsStr::to_str) {
        Some("gz") => {
            debug!("{:?} has .gz extension, extracting", path);
            read_to_end_limited(GzDecoder::new(data.as_slice()), limit)
        }
        Some("zip") => {
            debug!("{:?} has .zip extension, extracting", path);
            let mut zip = ZipArchive::new(Cursor::new(data))?;
            // Considering only the first file in the zip
            // There should be only one anyway
            let first_file = zip.by_index(0)?;
            read_to_end_limited(first_file, limit)
        }
        // Let's assume everything else is a text file
        _ => {
            debug!(
                "{:?} has no compressed file extension, no extraction needed",
                path
            );
            Ok(data)
        }
    }
}

/// Reads `reader` to the end, failing if it yields more than `limit` bytes.
fn read_to_end_limited<R: Read>(reader: R, limit: u64) -> Result<Vec<u8>, Error> {
    let mut content = vec![];
    // Read one byte past the limit to tell "exactly at the limit" from "over it".
    reader
        .take(limit.saturating_add(1))
        .read_to_end(&mut content)?;
    if content.len() as u64 > limit {
        return Err(RudderError::UncompressedSizeLimit(limit).into());
    }
    Ok(content)
}

/// Parses an S/MIME message as an UTF-8 string and validates the signature with the given
/// certificates.
/// * `input` is the signed content we want to check
/// * `certs` are the known valid certs for the node we are checking signed content from
///
/// Signatures use the following openssl options
/// * `-text` to add a text mime header, as it is not part of agent output
///   It also replaces all line endings by CRLF. It is necessary for the runlog
///   to be correctly read by this function.
///   Note: `-binary` is not valid S/MIME and default is missing the header.
/// * `-nocerts` to avoid including certs in the signature, as we use the known
///   certificate on the server to validate signature and embedded certs are ignored.
///
/// Agents do not pass `-md` and rely on OpenSSL's default digest for RSA keys (sha256).
/// Only sha256 and sha512 are accepted, as required by S/MIME 4.0 (RFC 8551).
pub fn signature(input: &[u8], certs: &Stack<X509>) -> Result<String, Error> {
    let (signature, content) = Pkcs7::from_smime(input)?;

    // An empty content is possible in S/MIME, but is it an
    // error in the Rudder context.
    let content = content.ok_or(RudderError::EmptyRunlog)?;

    // OpenSSL verifies any digest it supports, including MD5 and SHA-1.
    // Every signer must pass, as `verify` requires all of them to be valid.
    check_signer_digests(&signature)?;

    let mut flags = Pkcs7Flags::empty();
    // To remove text header
    flags.set(Pkcs7Flags::TEXT, true);
    // Ignore certificates contained in the message, we only rely on the one we know
    // Our messages should not contain certs anyway
    flags.set(Pkcs7Flags::NOINTERN, true);
    // Do not verify chain (as we have no meaningful chaining)
    // Only verify that the provided cert has signed the message
    flags.set(Pkcs7Flags::NOVERIFY, true);
    // No chaining so no need for a CA store
    let store = X509StoreBuilder::new()?.build();

    let mut message = vec![];
    signature.verify(certs, &store, Some(&content), Some(&mut message), flags)?;

    // We have validated the presence of a plain text MIME type, let's parse
    // content as a string.
    // Don't fail on non UTF-8 input
    Ok(String::from_utf8_lossy(&message).into_owned())
}

/// Refuses messages that are not signed data, or with a signer using a digest
/// algorithm we do not accept.
fn check_signer_digests(message: &Pkcs7Ref) -> Result<(), RudderError> {
    if message.signed().is_none() {
        return Err(RudderError::InvalidRunLog(
            "S/MIME content is not PKCS#7 signed data".to_string(),
        ));
    }
    for digest in signer_digests(message) {
        if HashType::from_nid(digest).is_none() {
            return Err(RudderError::UnsupportedSignatureDigest {
                invalid: digest
                    .short_name()
                    .map_or_else(|_| format!("{digest:?}"), str::to_string),
                valid: HashType::ACCEPTED,
            });
        }
    }
    Ok(())
}

/// Digest algorithm declared by each SignerInfo of `message`.
///
/// The `openssl` crate has no SignerInfo accessors (as of 0.10.82), hence the FFI.
fn signer_digests(message: &Pkcs7Ref) -> impl Iterator<Item = Nid> + '_ {
    // SAFETY: `message` is a valid PKCS7. The returned stack is owned by `message` (NULL when
    // it is not signed data) and the borrow ties it to `message`'s lifetime.
    let signer_infos = unsafe {
        let ptr = openssl_sys::PKCS7_get_signer_info(message.as_ptr());
        (!ptr.is_null()).then(|| StackRef::<Pkcs7SignerInfo>::from_ptr(ptr))
    };
    signer_infos.into_iter().flatten().map(|signer_info| {
        let mut digest_alg = std::ptr::null_mut();
        // SAFETY: `signer_info` is valid, the unused out-parameters may be NULL, and
        // `digest_alg` points into `signer_info` (not freed here), used only within this borrow.
        unsafe {
            openssl_sys::PKCS7_SIGNER_INFO_get0_algs(
                signer_info.as_ptr(),
                std::ptr::null_mut(),
                &mut digest_alg,
                std::ptr::null_mut(),
            );
            if digest_alg.is_null() {
                // Mandatory in the ASN.1 structure, refuse anyway
                Nid::UNDEF
            } else {
                X509AlgorithmRef::from_ptr(digest_alg).object().nid()
            }
        }
    })
}

#[cfg(test)]
mod tests {
    use std::fs::read_to_string;

    use super::*;

    const TEST_LIMIT: u64 = 10 * 1024 * 1024;

    #[tokio::test]
    async fn it_reads_gzipped_files() {
        let reference = read("tests/files/gz/normal.log").await.unwrap();
        assert_eq!(
            read_compressed_file("tests/files/gz/normal.log.gz", TEST_LIMIT)
                .await
                .unwrap(),
            reference
        );
    }

    #[tokio::test]
    async fn it_reads_zipped_files() {
        let reference = read("tests/files/gz/normal.log").await.unwrap();
        assert_eq!(
            read_compressed_file("tests/files/gz/normal.log.zip", TEST_LIMIT)
                .await
                .unwrap(),
            reference
        );
    }

    #[tokio::test]
    async fn it_refuses_compressed_files_over_the_size_limit() {
        let reference_len = read("tests/files/gz/normal.log").await.unwrap().len() as u64;

        for compressed in [
            "tests/files/gz/normal.log.gz",
            "tests/files/gz/normal.log.zip",
        ] {
            let data = read(compressed).await.unwrap();
            // Exactly at the limit is accepted
            assert_eq!(
                decompress(Path::new(compressed), data.clone(), reference_len)
                    .unwrap()
                    .len() as u64,
                reference_len
            );
            // One byte over is refused, not truncated
            let err = decompress(Path::new(compressed), data, reference_len - 1).unwrap_err();
            assert!(matches!(
                err.downcast_ref::<RudderError>(),
                Some(RudderError::UncompressedSizeLimit(_))
            ));
        }
    }

    #[tokio::test]
    async fn it_fails_on_corrupted_zip_content() {
        let mut data = read("tests/files/gz/normal.log.zip").await.unwrap();
        // Corrupt the compressed stream of the first entry, after its local header
        for b in &mut data[100..200] {
            *b = !*b;
        }
        assert!(decompress(Path::new("corrupted.zip"), data, TEST_LIMIT).is_err());
    }

    #[tokio::test]
    async fn it_reads_plain_files() {
        let reference = read("tests/files/gz/normal.log").await.unwrap();
        assert_eq!(
            read_compressed_file("tests/files/gz/normal.log", TEST_LIMIT)
                .await
                .unwrap(),
            reference
        );
    }

    fn certs(path: &str) -> Stack<X509> {
        let mut certs = Stack::new().unwrap();
        certs
            .push(X509::from_pem(&std::fs::read(path).unwrap()).unwrap())
            .unwrap();
        certs
    }

    fn node_certs() -> Stack<X509> {
        certs("tests/files/keys/e745a140-40bc-4b86-b6dc-084488fc906b.cert")
    }

    #[test]
    fn it_reads_signed_content() {
        // unix2dos normal.log
        let reference = read_to_string("tests/files/smime/normal.log").unwrap();

        // See tests/files/smime/README for how the files were signed
        for signed in [
            "tests/files/smime/normal.signed",
            "tests/files/smime/normal-sha512.signed",
        ] {
            assert_eq!(
                signature(&std::fs::read(signed).unwrap(), &node_certs()).unwrap(),
                reference
            );
        }
    }

    #[test]
    fn it_refuses_weak_signature_digests() {
        for (signed, digest) in [
            ("tests/files/smime/normal-sha1.signed", "SHA1"),
            ("tests/files/smime/normal-md5.signed", "MD5"),
        ] {
            let input = std::fs::read(signed).unwrap();

            // The signature itself is valid, OpenSSL alone would accept it
            let (message, content) = Pkcs7::from_smime(&input).unwrap();
            let mut flags = Pkcs7Flags::empty();
            flags.set(
                Pkcs7Flags::TEXT | Pkcs7Flags::NOINTERN | Pkcs7Flags::NOVERIFY,
                true,
            );
            message
                .verify(
                    &node_certs(),
                    &X509StoreBuilder::new().unwrap().build(),
                    content.as_deref(),
                    None,
                    flags,
                )
                .unwrap();

            let err = signature(&input, &node_certs()).unwrap_err();
            assert!(
                matches!(
                    err.downcast_ref::<RudderError>(),
                    Some(RudderError::UnsupportedSignatureDigest { invalid, .. }) if invalid == digest
                ),
                "{signed}: {err}"
            );
        }
    }

    #[test]
    fn it_detects_wrong_content() {
        assert!(
            signature(
                &std::fs::read("tests/files/smime/normal-diff.signed").unwrap(),
                &node_certs(),
            )
            .is_err()
        );
    }

    #[test]
    fn it_detects_wrong_certificates() {
        assert!(
            signature(
                &std::fs::read("tests/files/smime/normal.signed").unwrap(),
                &certs("tests/files/keys/e745a140-40bc-4b86-b6dc-084488fc906b-other.cert"),
            )
            .is_err()
        );
    }
}
