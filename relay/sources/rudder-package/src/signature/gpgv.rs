// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

use crate::cmd::CmdOutput;
use crate::signature::{SignatureVerifier, VerificationSuccess};
use anyhow::bail;

use std::fs::read_to_string;
use std::path::{Path, PathBuf};
use std::process::Command;

#[derive(Clone, Debug)]
pub struct SignatureVerifierGpgv {
    keyring_path: PathBuf,
}

impl SignatureVerifierGpgv {
    #[allow(dead_code)]
    pub fn new(keyring_path: PathBuf) -> Self {
        Self { keyring_path }
    }
}

impl SignatureVerifier for SignatureVerifierGpgv {
    /// Verify against a detached signature using gpgv
    fn verify(&self, signature: &Path, data: &Path) -> anyhow::Result<VerificationSuccess> {
        const GPGV_BIN: &str = "gpgv";
        let mut gpgv = Command::new(GPGV_BIN);
        let cmd = gpgv
            .arg("--keyring")
            .arg(&self.keyring_path)
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
            // `gpgv` does not return the signed data, so we read it from the file.
            // It is ok as only root can write to the file.
            Ok(VerificationSuccess::ValidSignature(read_to_string(data)?))
        } else {
            bail!(
                "Invalid signature file '{}' for '{}', gpgv failed with:\n{}",
                signature.display(),
                data.display(),
                String::from_utf8_lossy(&gpg_output.output.stderr)
            )
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    #[test]
    fn it_verifies_signature() {
        let verifier = SignatureVerifierGpgv::new(PathBuf::from("tools/rudder_plugins_key.gpg"));

        assert!(
            verifier
                .verify(
                    Path::new("tests/signature/SHA512SUMS.asc"),
                    Path::new("tests/signature/SHA512SUMS")
                )
                .is_ok()
        );
        assert!(
            verifier
                .verify(
                    Path::new("tests/signature/SHA512SUMS.asc"),
                    Path::new("tests/signature/SHA512SUMS.wrong")
                )
                .is_err()
        );
    }
}
