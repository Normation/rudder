// Copyright 2019 Normation SAS
//
// This file is part of Rudder.
//
// Rudder is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// In accordance with the terms of section 7 (7. Additional Terms.) of
// the GNU General Public License version 3, the copyright holders add
// the following Additional permissions:
// Notwithstanding to the terms of section 5 (5. Conveying Modified Source
// Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
// Public License version 3, when you create a Related Module, this
// Related Module is not considered as a part of the work and may be
// distributed under the license agreement of your choice.
// A "Related Module" means a set of sources files including their
// documentation that, without modification of the Source Code, enables
// supplementary functions or services in addition to those offered by
// the Software.
//
// Rudder is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

pub mod watch;

use crate::error::Error;
use flate2::read::GzDecoder;
use openssl::{
    pkcs7::{Pkcs7, Pkcs7Flags},
    stack::Stack,
    x509::{store::X509StoreBuilder, X509},
};
use std::{
    ffi::OsStr,
    fs::read,
    io::{Cursor, Read},
    path::Path,
};
use tracing::debug;
use zip::read::ZipArchive;

pub fn read_compressed_file<P: AsRef<Path>>(path: P) -> Result<Vec<u8>, Error> {
    let path = path.as_ref();

    debug!("Reading {:#?} content", path);
    let data = read(path)?;

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
pub fn signature(input: &[u8], certs: &Stack<X509>) -> Result<String, Error> {
    let (signature, content) = Pkcs7::from_smime(input)?;

    // An empty content is possible in S/MIME, but is it an
    // error in the Rudder context.
    let content = content.ok_or(Error::EmptyRunlog)?;

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
    Ok(String::from_utf8(message)?)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs::read_to_string;

    #[test]
    fn it_reads_gzipped_files() {
        let reference = read("tests/files/gz/normal.log").unwrap();
        assert_eq!(
            read_compressed_file("tests/files/gz/normal.log.gz").unwrap(),
            reference
        );
    }

    #[test]
    fn it_reads_zipped_files() {
        let reference = read("tests/files/gz/normal.log").unwrap();
        assert_eq!(
            read_compressed_file("tests/files/gz/normal.log.zip").unwrap(),
            reference
        );
    }

    #[test]
    fn it_reads_plain_files() {
        let reference = read("tests/files/gz/normal.log").unwrap();
        assert_eq!(
            read_compressed_file("tests/files/gz/normal.log").unwrap(),
            reference
        );
    }

    #[test]
    fn it_reads_signed_content() {
        // unix2dos normal.log
        let reference = read_to_string("tests/files/smime/normal.log").unwrap();

        let x509 = X509::from_pem(
            &read("tests/files/keys/e745a140-40bc-4b86-b6dc-084488fc906b.cert").unwrap(),
        )
        .unwrap();

        // Certs
        let mut certs = Stack::new().unwrap();
        certs.push(x509).unwrap();

        assert_eq!(
            // openssl smime -sign -signer ../keys/e745a140-40bc-4b86-b6dc-084488fc906b.cert
            //         -in normal.log -out normal.signed -inkey ../keys/e745a140-40bc-4b86-b6dc-084488fc906b.priv
            //         -passin "pass:Cfengine passphrase" -text -nocerts -md sha256
            signature(&read("tests/files/smime/normal.signed").unwrap(), &certs,).unwrap(),
            reference
        );
    }

    #[test]
    fn it_detects_wrong_content() {
        let x509 = X509::from_pem(
            &read("tests/files/keys/e745a140-40bc-4b86-b6dc-084488fc906b.cert").unwrap(),
        )
        .unwrap();
        let mut certs = Stack::new().unwrap();
        certs.push(x509).unwrap();

        assert!(signature(
            &read("tests/files/smime/normal-diff.signed").unwrap(),
            &certs,
        )
        .is_err());
    }

    #[test]
    fn it_detects_wrong_certificates() {
        let x509bis = X509::from_pem(
            &read("tests/files/keys/e745a140-40bc-4b86-b6dc-084488fc906b-other.cert").unwrap(),
        )
        .unwrap();
        let mut certs = Stack::new().unwrap();
        certs.push(x509bis).unwrap();

        assert!(signature(&read("tests/files/smime/normal.signed").unwrap(), &certs,).is_err());
    }
}
