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
use openssl::x509::store::X509Store;
use openssl::{
    pkcs7::{Pkcs7, Pkcs7Flags},
    stack::Stack,
    x509::X509,
};
use tokio::fs::read;
use tracing::debug;
use zip::read::ZipArchive;

use crate::error::RudderError;

pub async fn read_compressed_file<P: AsRef<Path>>(path: P) -> Result<Vec<u8>, Error> {
    let path = path.as_ref();

    debug!("Reading {:#?} content", path);
    let data = read(path).await?;

    Ok(match path.extension().and_then(OsStr::to_str) {
        Some("gz") => {
            debug!("{:?} has .gz extension, extracting", path);
            let mut gz = GzDecoder::new(data.as_slice());
            let mut uncompressed_data = vec![];
            gz.read_to_end(&mut uncompressed_data)?;
            uncompressed_data
        }
        Some("zip") => {
            debug!("{:?} has .zip extension, extracting", path);
            let mut zip = ZipArchive::new(Cursor::new(data))?;
            // Considering only the first file in the zip
            // There should be only one anyway
            let mut first_file = zip.by_index(0)?;
            let mut uncompressed_data: Vec<u8> = Vec::new();
            let _ = first_file.read_to_end(&mut uncompressed_data);
            uncompressed_data
        }
        // Let's assume everything else is a text file
        _ => {
            debug!(
                "{:?} has no compressed file extension, no extraction needed",
                path
            );
            data
        }
    })
}

/// Parses an S/MIME message as an UTF-8 string and validates the signature with the given
/// certificates.
/// * `input` is the signed content we want to check
/// * `certs` are the known valid certs for the node we are checking signed content from
/// * `ca_store` when in pinning mode, it is empty, and certs contains the list of certs.
///
/// Signatures use the following openssl options
/// * `-text` to add a text mime header, as it is not part of agent output
///   It also replaces all line endings by CRLF. It is necessary for the runlog
///   to be correctly read by this function.
///   Note: `-binary` is not valid S/MIME and default is missing the header.
/// * `-md sha256` to force sha256 hash.
///   FIXME: refuse weaker hashes in signature verification.
/// * `-nocerts` to avoid including certs in the signature, as we use the known
///   certificate on the server to validate signature and embedded certs are ignored.
pub fn verify_signature(
    input: &[u8],
    // FIXME send certificates in reports
    // And check ID in report
    certs: &Stack<X509>,
    ca_store: &X509Store,
    verify_certificate_chain: bool,
) -> Result<String, Error> {
    let (signature, content) = Pkcs7::from_smime(input)?;

    // An empty content is possible in S/MIME, but is it an
    // error in the Rudder context.
    let content = content.ok_or(RudderError::EmptyRunlog)?;

    let mut flags = Pkcs7Flags::empty();
    // To remove text header
    flags.set(Pkcs7Flags::TEXT, true);
    // Ignore certificates contained in the message, we only rely on the one we know
    // Our messages should not contain certs anyway
    flags.set(Pkcs7Flags::NOINTERN, true);
    if !verify_certificate_chain {
        // Do not verify chain (as we have no meaningful chaining)
        // Only verify that the provided cert has signed the message
        flags.set(Pkcs7Flags::NOVERIFY, true);
    }

    let mut message = vec![];
    signature.verify(certs, ca_store, Some(&content), Some(&mut message), flags)?;

    // We have validated the presence of a plain text MIME type, let's parse
    // content as a string.
    // Don't fail on non UTF-8 input
    Ok(String::from_utf8_lossy(&message).into_owned())
}

#[cfg(test)]
mod tests {
    use super::*;
    use openssl::x509::store::X509StoreBuilder;
    use std::fs::read_to_string;

    #[tokio::test]
    async fn it_reads_gzipped_files() {
        let reference = read("tests/files/gz/normal.log").await.unwrap();
        assert_eq!(
            read_compressed_file("tests/files/gz/normal.log.gz")
                .await
                .unwrap(),
            reference
        );
    }

    #[tokio::test]
    async fn it_reads_zipped_files() {
        let reference = read("tests/files/gz/normal.log").await.unwrap();
        assert_eq!(
            read_compressed_file("tests/files/gz/normal.log.zip")
                .await
                .unwrap(),
            reference
        );
    }

    #[tokio::test]
    async fn it_reads_plain_files() {
        let reference = read("tests/files/gz/normal.log").await.unwrap();
        assert_eq!(
            read_compressed_file("tests/files/gz/normal.log")
                .await
                .unwrap(),
            reference
        );
    }

    #[test]
    fn it_reads_signed_content() {
        // unix2dos normal.log
        let reference = read_to_string("tests/files/smime/normal.log").unwrap();

        let x509 = X509::from_pem(
            &std::fs::read("tests/files/keys/e745a140-40bc-4b86-b6dc-084488fc906b.cert").unwrap(),
        )
        .unwrap();

        // Certs
        let mut certs = Stack::new().unwrap();
        certs.push(x509).unwrap();

        assert_eq!(
            // openssl smime -sign -signer ../keys/e745a140-40bc-4b86-b6dc-084488fc906b.cert
            //         -in normal.log -out normal.signed -inkey ../keys/e745a140-40bc-4b86-b6dc-084488fc906b.priv
            //         -passin "pass:Cfengine passphrase" -text -nocerts -md sha256
            verify_signature(
                &std::fs::read("tests/files/smime/normal.signed").unwrap(),
                &certs,
                &X509StoreBuilder::new().unwrap().build(),
                false
            )
            .unwrap(),
            reference
        );
    }

    #[test]
    fn it_detects_wrong_content() {
        let x509 = X509::from_pem(
            &std::fs::read("tests/files/keys/e745a140-40bc-4b86-b6dc-084488fc906b.cert").unwrap(),
        )
        .unwrap();
        let mut certs = Stack::new().unwrap();
        certs.push(x509).unwrap();

        assert!(verify_signature(
            &std::fs::read("tests/files/smime/normal-diff.signed").unwrap(),
            &certs,
            &X509StoreBuilder::new().unwrap().build(),
            false
        )
        .is_err());
    }

    #[test]
    fn it_detects_wrong_certificates() {
        let x509bis = X509::from_pem(
            &std::fs::read("tests/files/keys/e745a140-40bc-4b86-b6dc-084488fc906b-other.cert")
                .unwrap(),
        )
        .unwrap();
        let mut certs = Stack::new().unwrap();
        certs.push(x509bis).unwrap();

        assert!(verify_signature(
            &std::fs::read("tests/files/smime/normal.signed").unwrap(),
            &certs,
            &X509StoreBuilder::new().unwrap().build(),
            false
        )
        .is_err());
    }

    #[test]
    fn it_reads_signed_content_with_ca() {
        let reference = read_to_string("tests/files/ca/file").unwrap();

        let ca_cert = X509::from_pem(&std::fs::read("tests/files/ca/CA.pem").unwrap()).unwrap();
        let agent_cert =
            X509::from_pem(&std::fs::read("tests/files/ca/agent1.pem").unwrap()).unwrap();

        // Certs
        let mut certs = Stack::new().unwrap();
        certs.push(agent_cert).unwrap();

        let mut ca_store = X509StoreBuilder::new().unwrap();
        ca_store.add_cert(ca_cert).unwrap();
        let ca_store = ca_store.build();

        assert_eq!(
            // openssl smime -sign -signer ../keys/e745a140-40bc-4b86-b6dc-084488fc906b.cert
            //         -in normal.log -out normal.signed -inkey ../keys/e745a140-40bc-4b86-b6dc-084488fc906b.priv
            //         -passin "pass:Cfengine passphrase" -text -nocerts -md sha256
            verify_signature(
                &std::fs::read("tests/files/ca/file.signed").unwrap(),
                &certs,
                &ca_store,
                true
            )
            .unwrap(),
            reference
        );
    }
}
