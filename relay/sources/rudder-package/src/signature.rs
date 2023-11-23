// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

//! Here we use gpgv:
//!
//! * It is a bit lighter than the full gpg implementation, with a way simpler CLI, but still provided
//!   everywhere.
//! * sequoia is to low-level for our goals, we don't want to have to deal with gpg internals.
//! * Other Rust implementation do not seem mature enough.

const GPGV_BIN: &str = "/usr/bin/gpgv";

use std::{
    fs::{read_to_string, File},
    io,
    os::unix::ffi::OsStrExt,
    path::{Path, PathBuf},
    process::Command,
    str,
};

use anyhow::{bail, Result};
use regex::Regex;
use sha2::{Digest, Sha512};

#[derive(Copy, Clone, PartialEq, Eq, Debug)]
pub enum VerificationSuccess {
    ValidSignature,
    ValidSignatureAndHash,
}

use crate::cmd::CmdOutput;

#[derive(Clone, PartialEq, Eq, Debug)]
pub struct SignatureVerifier {
    keyring: PathBuf,
}

impl SignatureVerifier {
    pub fn new(keyring: PathBuf) -> Self {
        Self { keyring }
    }

    fn verify(&self, signature: &Path, data: &Path) -> Result<VerificationSuccess> {
        let mut gpgv = Command::new(GPGV_BIN);
        let cmd = gpgv
            .arg("--keyring")
            .arg(&self.keyring)
            .arg("--")
            .arg(signature)
            .arg(data);
        let gpg_output = match CmdOutput::new(cmd) {
            Ok(out) => out,
            Err(e) => {
                bail!(
                    "Could not check signature using gpgv, most likely because it is not installed:\n{}",
                    e
                );
            }
        };
        if gpg_output.output.status.success() {
            Ok(VerificationSuccess::ValidSignature)
        } else {
            bail!(
                "Invalid signature file '{}' for '{}', gpgv failed with:\n{}",
                signature.display(),
                data.display(),
                String::from_utf8_lossy(&gpg_output.output.stderr)
            )
        }
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
        let v = self.verify(hash_sign_file, hash_file)?;
        assert_eq!(v, VerificationSuccess::ValidSignature);
        let hash_r = read_to_string(hash_file)?;
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
    fn it_verifies_signature() {
        let verifier = SignatureVerifier::new(PathBuf::from("tools/rudder_plugins_key.gpg"));
        assert!(verifier
            .verify(
                Path::new("tests/signature/SHA512SUMS.asc"),
                Path::new("tests/signature/SHA512SUMS")
            )
            .is_ok());
        assert!(verifier
            .verify(
                Path::new("tests/signature/SHA512SUMS.asc"),
                Path::new("tests/signature/SHA512SUMS.wrong")
            )
            .is_err());
    }

    #[test]
    fn it_verifies_files() {
        let verifier = SignatureVerifier::new(PathBuf::from("tools/rudder_plugins_key.gpg"));
        assert!(verifier
            .verify_file(
                Path::new("tests/signature/rudder-plugin-zabbix-8.0.3-2.1.rpkg"),
                Path::new("tests/signature/SHA512SUMS.asc"),
                Path::new("tests/signature/SHA512SUMS"),
            )
            .is_ok());
        assert!(verifier
            .verify_file(
                Path::new("tests/signature/rudder-plugin-zabbix-8.0.0-2.1.rpkg"),
                Path::new("tests/signature/SHA512SUMS.asc"),
                Path::new("tests/signature/SHA512SUMS"),
            )
            .is_err());
    }

    #[test]
    fn it_hashes_files() {
        assert_eq!(SignatureVerifier::file_sha512(Path::new("tests/hash/poem.txt")).unwrap(), "5074b6b33568c923c70931ed89a4182aeb5f1ecffaac10067149211a0466748c577385e9edfcefc6b83c2f407e70718d529e07f19a1dab84df1b4b2cd03faba6");
    }
}
