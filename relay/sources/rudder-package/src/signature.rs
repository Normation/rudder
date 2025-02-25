// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

use anyhow::{Result, bail};
use openpgp::parse::{Parse, stream::*};
use openpgp::policy::StandardPolicy;
use openpgp::{Cert, KeyHandle};
use regex::Regex;
use sequoia_openpgp as openpgp;
use sequoia_openpgp::cert::CertParser;
use sha2::{Digest, Sha512};
use std::fs::read;
use std::{
    fs::{File, read_to_string},
    io,
    os::unix::ffi::OsStrExt,
    path::{Path, PathBuf},
    str,
};

#[derive(Copy, Clone, PartialEq, Eq, Debug)]
pub enum VerificationSuccess {
    ValidSignature,
    ValidSignatureAndHash,
}

#[derive(Clone, Debug)]
pub struct SignatureVerifier {
    /// The configured certificates.
    keyring: Vec<Cert>,
}

#[derive(Clone, Debug)]
struct Helper<'a> {
    certs: &'a [Cert],
}

impl VerificationHelper for Helper<'_> {
    fn get_certs(&mut self, ids: &[KeyHandle]) -> openpgp::Result<Vec<Cert>> {
        for id in ids {
            for cert in self.certs {
                if cert.key_handle().aliases(id) {
                    return Ok(vec![cert.clone()]);
                }
            }
        }
        bail!("Could not find key")
    }

    fn check(&mut self, structure: MessageStructure) -> openpgp::Result<()> {
        // Per `DetachedVerifierBuilder` docs:
        //
        // > `VerificationHelper::check` will be called with a `MessageStructure` containing exactly
        // > one layer, a signature group.

        if structure.len() != 1 {
            bail!("Unexpected number of layers");
        }

        match structure[0] {
            MessageLayer::Encryption { .. } => bail!("Unexpected encryption layer"),
            MessageLayer::Compression { .. } => bail!("Unexpected compression layer"),
            MessageLayer::SignatureGroup { ref results } => {
                if !results.iter().any(|r| r.is_ok()) {
                    bail!("No valid signature found");
                }
            }
        }

        Ok(())
    }
}

impl SignatureVerifier {
    pub fn new(keyring: PathBuf) -> Result<Self> {
        let cert_parser = CertParser::from_file(&keyring)?;
        let keyring: Vec<Cert> = cert_parser.collect::<Result<Vec<Cert>>>()?;
        Ok(Self { keyring })
    }

    /// Verify data against a detached signature using sequoia
    fn verify_sq(&self, signature: &[u8], data: &[u8]) -> Result<VerificationSuccess> {
        let policy = StandardPolicy::new();
        // Use current time
        let time = None;
        let helper = Helper {
            certs: &self.keyring,
        };

        let mut verifier =
            DetachedVerifierBuilder::from_bytes(signature)?.with_policy(&policy, time, helper)?;
        verifier.verify_bytes(data)?;

        Ok(VerificationSuccess::ValidSignature)
    }

    /// Returns hexadecimal encoded sha512 hash of the file
    fn file_sha512(path: &Path) -> Result<String> {
        let mut hasher = Sha512::new();
        let mut file = File::open(path)?;
        io::copy(&mut file, &mut hasher)?;
        let hash = hasher.finalize();
        Ok(base16ct::lower::encode_string(&hash))
    }

    fn lookup_hashes(name: &str, hash_list: &str) -> Vec<String> {
        let re = Regex::new(r"(?<h>[0-9a-f]{128}) +(?<f>[0-9a-zA-Z~.\-_]+)").unwrap();
        let mut res = vec![];
        for line in hash_list.lines() {
            if let Some(caps) = re.captures(line) {
                let hash = caps.name("h").unwrap().as_str();
                let hash_file_name = caps.name("f").unwrap().as_str();
                if name == hash_file_name {
                    res.push(hash.to_string())
                }
            }
        }
        res
    }

    pub fn verify_file(
        &self,
        file: &Path,
        hash_sign_file: &Path,
        hash_file: &Path,
    ) -> Result<VerificationSuccess> {
        // Read files, hash file contains UTF-8 text.
        let hash_r = read_to_string(hash_file)?;
        let sign_r = read(hash_sign_file)?;

        let v = self.verify_sq(&sign_r, hash_r.as_bytes())?;
        assert_eq!(v, VerificationSuccess::ValidSignature);
        let file_hash = Self::file_sha512(file)?;
        let file_name = String::from_utf8_lossy(file.file_name().unwrap().as_bytes());

        let hashes = Self::lookup_hashes(&file_name, &hash_r);
        match hashes.len() {
            0 => bail!("Could not find hash for '{}' in hash file", file_name,),
            1 => {
                if hashes[0] == file_hash {
                    Ok(VerificationSuccess::ValidSignatureAndHash)
                } else {
                    bail!(
                        "Hash in file does not match downloaded file ({} != {})",
                        hashes[0],
                        file_hash
                    )
                }
            }
            _ => bail!("Several hashes for '{}', stopping", file_name),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn it_verifies_signature_with_sequoia() {
        let verifier =
            SignatureVerifier::new(PathBuf::from("tools/rudder_plugins_key.gpg")).unwrap();

        assert!(
            verifier
                .verify_sq(
                    &read(Path::new("tests/signature/SHA512SUMS.asc")).unwrap(),
                    &read(Path::new("tests/signature/SHA512SUMS")).unwrap()
                )
                .is_ok()
        );
        assert!(
            verifier
                .verify_sq(
                    &read(Path::new("tests/signature/SHA512SUMS.asc")).unwrap(),
                    &read(Path::new("tests/signature/SHA512SUMS.wrong")).unwrap()
                )
                .is_err()
        );
    }

    #[test]
    fn it_verifies_files() {
        let verifier =
            SignatureVerifier::new(PathBuf::from("tools/rudder_plugins_key.gpg")).unwrap();
        assert!(
            verifier
                .verify_file(
                    Path::new("tests/signature/rudder-plugin-zabbix-8.0.3-2.1.rpkg"),
                    Path::new("tests/signature/SHA512SUMS.asc"),
                    Path::new("tests/signature/SHA512SUMS"),
                )
                .is_ok()
        );
        assert!(
            verifier
                .verify_file(
                    Path::new("tests/signature/rudder-plugin-zabbix-8.0.0-2.1.rpkg"),
                    Path::new("tests/signature/SHA512SUMS.asc"),
                    Path::new("tests/signature/SHA512SUMS"),
                )
                .is_err()
        );
    }

    #[test]
    fn it_hashes_files() {
        assert_eq!(
            SignatureVerifier::file_sha512(Path::new("tests/hash/poem.txt")).unwrap(),
            "5074b6b33568c923c70931ed89a4182aeb5f1ecffaac10067149211a0466748c577385e9edfcefc6b83c2f407e70718d529e07f19a1dab84df1b4b2cd03faba6"
        );
    }
}
