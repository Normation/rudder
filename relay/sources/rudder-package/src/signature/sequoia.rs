// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

use crate::signature::{SignatureVerifier, VerificationSuccess};
use anyhow::bail;
use sequoia_openpgp::cert::CertParser;
use sequoia_openpgp::parse::Parse;
use sequoia_openpgp::parse::stream::{
    DetachedVerifierBuilder, MessageLayer, MessageStructure, VerificationHelper,
};
use sequoia_openpgp::policy::StandardPolicy;
use sequoia_openpgp::{Cert, KeyHandle};
use std::fs::{read, read_to_string};
use std::path::{Path, PathBuf};

#[derive(Clone, Debug)]
pub struct SignatureVerifierSequoia {
    /// The configured certificates.
    #[cfg(feature = "sequoia")]
    keyring: Vec<Cert>,
}

impl SignatureVerifierSequoia {
    pub fn new(keyring_path: PathBuf) -> anyhow::Result<Self> {
        let cert_parser = CertParser::from_file(&keyring_path)?;
        let keyring: Vec<Cert> = cert_parser.collect::<anyhow::Result<Vec<Cert>>>()?;
        Ok(Self { keyring })
    }
}

impl SignatureVerifier for SignatureVerifierSequoia {
    /// Verify data against a detached signature using sequoia
    fn verify(&self, signature: &Path, data: &Path) -> anyhow::Result<VerificationSuccess> {
        // Read files contains UTF-8 text.
        let data = read_to_string(data)?;
        let signature = read(signature)?;

        let policy = StandardPolicy::new();
        // Use current time
        let time = None;
        let helper = Helper {
            certs: &self.keyring,
        };

        let mut verifier =
            DetachedVerifierBuilder::from_bytes(&signature)?.with_policy(&policy, time, helper)?;
        verifier.verify_bytes(&data)?;

        Ok(VerificationSuccess::ValidSignature(data))
    }
}

#[derive(Clone, Debug)]
struct Helper<'a> {
    certs: &'a [Cert],
}

impl VerificationHelper for Helper<'_> {
    fn get_certs(&mut self, ids: &[KeyHandle]) -> sequoia_openpgp::Result<Vec<Cert>> {
        for id in ids {
            for cert in self.certs {
                if cert.key_handle().aliases(id) {
                    return Ok(vec![cert.clone()]);
                }
            }
        }
        bail!("Could not find key")
    }

    fn check(&mut self, structure: MessageStructure) -> sequoia_openpgp::Result<()> {
        // Per `DetachedVerifierBuilder` docs:
        //
        // > `VerificationHelper::check` will be called with a `MessageStructure` containing exactly
        // > one layer, a signature group.

        let mut iter = structure.into_iter();
        let s = iter
            .next()
            .ok_or_else(|| anyhow::anyhow!("Unexpected number of layers"))?;

        if iter.count() != 0 {
            bail!("Unexpected number of layers");
        }

        match s {
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::signature::sequoia::SignatureVerifierSequoia;
    use std::path::PathBuf;

    #[test]
    fn it_verifies_signature_with_sequoia() {
        let verifier =
            SignatureVerifierSequoia::new(PathBuf::from("tools/rudder_plugins_key.asc")).unwrap();

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
