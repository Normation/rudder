// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

mod gpgv;
#[cfg(feature = "sequoia")]
pub(crate) mod sequoia;

use anyhow::{Result, bail};
use regex::Regex;
use sha2::{Digest, Sha512};
use std::path::PathBuf;
use std::{fs::File, io, os::unix::ffi::OsStrExt, path::Path, str};

pub fn verifier(keyring_path: PathBuf) -> Result<Box<dyn SignatureVerifier>> {
    #[cfg(feature = "sequoia")]
    let v = Box::new(sequoia::SignatureVerifierSequoia::new(
        keyring_path.with_extension("asc"),
    )?);
    #[cfg(not(feature = "sequoia"))]
    let v = Box::new(gpgv::SignatureVerifierGpgv::new(
        keyring_path.with_extension("gpg"),
    ));

    Ok(v)
}

pub trait SignatureVerifier {
    fn verify(&self, signature: &Path, data: &Path) -> Result<VerificationSuccess>;

    fn verify_file(
        &self,
        file: &Path,
        hash_sign_file: &Path,
        hash_file: &Path,
    ) -> Result<VerificationSuccess> {
        let v = self.verify(hash_sign_file, hash_file)?;
        let valid_hashes = match v {
            VerificationSuccess::ValidSignature(d) => d,
            VerificationSuccess::ValidSignatureAndHash => unreachable!(),
        };

        let file_hash = file_sha512(file)?;
        let file_name = String::from_utf8_lossy(file.file_name().unwrap().as_bytes());

        let hashes = lookup_hashes(&file_name, &valid_hashes);
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

#[derive(Clone, PartialEq, Eq, Debug)]
pub enum VerificationSuccess {
    ValidSignature(String),
    ValidSignatureAndHash,
}

/// Returns hexadecimal encoded sha512 hash of the file
///
/// Reads the file at once, not a problem for small files.
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

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    #[test]
    fn it_verifies_files() {
        let verifier = verifier(PathBuf::from("tools/rudder_plugins_key.gpg")).unwrap();
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
            file_sha512(Path::new("tests/hash/poem.txt")).unwrap(),
            "5074b6b33568c923c70931ed89a4182aeb5f1ecffaac10067149211a0466748c577385e9edfcefc6b83c2f407e70718d529e07f19a1dab84df1b4b2cd03faba6"
        );
    }
}
