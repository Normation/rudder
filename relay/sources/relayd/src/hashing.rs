// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use std::{fmt, str, str::FromStr};

use anyhow::{anyhow, Error};
use base64::{engine::general_purpose as base64_engine, Engine};
use openssl::hash::MessageDigest;
use sha2::{Digest, Sha256, Sha512};

use crate::error::RudderError;

#[derive(Clone, PartialEq, Eq, Default)]
pub struct Hash {
    pub hash_type: HashType,
    pub value: Vec<u8>,
}

impl fmt::Debug for Hash {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Hash")
            .field("hash_type", &self.hash_type)
            .field("value", &self.hex())
            .finish()
    }
}

impl Hash {
    pub fn new(hash_type: &str, hex_value: &str) -> Result<Hash, Error> {
        let hash_type = HashType::from_str(hash_type)?;
        let value = hex::decode(hex_value)?;

        if hash_type.is_valid_hash(&value) {
            Ok(Hash { hash_type, value })
        } else {
            Err(RudderError::InvalidHash(hex_value.to_string()).into())
        }
    }

    pub fn hex(&self) -> String {
        hex::encode(&self.value)
    }
}

impl FromStr for Hash {
    type Err = Error;

    // hash_type:hash for 6.x servers
    // hash_type//base64(hash) for 7.x servers
    fn from_str(s: &str) -> Result<Self, Self::Err> {
        // "//"" separator means base64 encoded hash
        let parts = s.splitn(2, "//").collect::<Vec<&str>>();
        if parts.len() == 2 {
            let hash_type = parts[0].parse::<HashType>()?;
            let hash = base64_engine::STANDARD.decode(parts[1])?;

            return if hash_type.is_valid_hash(&hash) {
                Ok(Hash {
                    hash_type,
                    value: hash,
                })
            } else {
                Err(anyhow!("Invalid {} hash: {}", hash_type, parts[1]))
            };
        }

        // ":" separator means raw hash
        let parts = s.splitn(2, ':').collect::<Vec<&str>>();
        if parts.len() == 2 {
            let hash_type = parts[0].parse::<HashType>()?;
            let hash = hex::decode(parts[1])?;

            if hash_type.is_valid_hash(&hash) {
                Ok(Hash {
                    hash_type,
                    value: hash,
                })
            } else {
                Err(anyhow!("Invalid {} hash: {}", hash_type, parts[1]))
            }
        } else {
            Err(anyhow!("Invalid hash: {}", s))
        }
    }
}

impl fmt::Display for Hash {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(
            f,
            "{}//{}",
            self.hash_type,
            base64_engine::STANDARD.encode(&self.value)
        )
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum HashType {
    Sha256,
    #[default]
    Sha512,
}

impl FromStr for HashType {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        match s {
            "sha256" => Ok(HashType::Sha256),
            "sha512" => Ok(HashType::Sha512),
            _ => Err(RudderError::InvalidHashType {
                invalid: s.to_string(),
                valid: "sha256, sha512",
            }
            .into()),
        }
    }
}

impl fmt::Display for HashType {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(
            f,
            "{}",
            match self {
                HashType::Sha256 => "sha256",
                HashType::Sha512 => "sha512",
            }
        )
    }
}

impl HashType {
    pub fn hash(self, bytes: &[u8]) -> Hash {
        let value = match self {
            HashType::Sha256 => {
                let mut hasher = Sha256::new();
                hasher.update(bytes);
                hasher.finalize()[..].to_owned()
            }
            HashType::Sha512 => {
                let mut hasher = Sha512::new();
                hasher.update(bytes);
                hasher.finalize()[..].to_owned()
            }
        };
        Hash {
            hash_type: self,
            value,
        }
    }

    pub fn to_openssl_hash(self) -> MessageDigest {
        match self {
            HashType::Sha256 => MessageDigest::sha256(),
            HashType::Sha512 => MessageDigest::sha512(),
        }
    }

    fn size_bytes(self) -> usize {
        match self {
            HashType::Sha256 => 32,
            HashType::Sha512 => 64,
        }
    }

    /// Check is an hexadecimal string represents a valid hash for the given type
    pub fn is_valid_hash(self, hash: &[u8]) -> bool {
        hash.len() == self.size_bytes()
    }
}

#[cfg(test)]
mod tests {
    use super::{HashType::*, *};

    #[test]
    fn it_parses_hash_types() {
        assert_eq!(HashType::from_str("sha256").unwrap(), Sha256);
        assert_eq!(HashType::from_str("sha512").unwrap(), Sha512);
        assert!(HashType::from_str("").is_err());
    }

    #[test]
    fn it_parses_hashes() {
        assert_eq!(
            Hash::from_str(
                "sha256:c22a3fb1e9de4bfa697ba258f60f14339b72c3faeb043cb75379b9ebcb2717c3"
            )
            .unwrap(),
            Hash {
                hash_type: Sha256,
                value: hex::decode(
                    "c22a3fb1e9de4bfa697ba258f60f14339b72c3faeb043cb75379b9ebcb2717c3"
                )
                .unwrap()
            }
        );
        assert_eq!(
            Hash::from_str("sha256//ZE9q37dB6Nq+ZJz1cdrfdt+qPL+Xk8sKkLDMTp4QemY=").unwrap(),
            Hash {
                hash_type: Sha256,
                value: hex::decode(
                    "644f6adfb741e8dabe649cf571dadf76dfaa3cbf9793cb0a90b0cc4e9e107a66"
                )
                .unwrap()
            }
        );
        assert_eq!(
            Hash::from_str(
                "sha512:d301df08cfc11928ee30b4624fbbb6aba068f06faa1c4d5e7516cf7f7b7cb36e8a38d9095ecaadef97882f093921096e9340d452b0c47e9854414e7c05e0c6c4"
            )
            .unwrap(),
            Hash {
                hash_type: Sha512,
                value: hex::decode("d301df08cfc11928ee30b4624fbbb6aba068f06faa1c4d5e7516cf7f7b7cb36e8a38d9095ecaadef97882f093921096e9340d452b0c47e9854414e7c05e0c6c4"
                    ).unwrap()
            }
        );
        assert!(Hash::from_str("").is_err());
        assert!(Hash::from_str("sha256:").is_err());
        assert!(Hash::from_str("sha256//").is_err());
        assert!(Hash::from_str("sha256//#$%^&*").is_err());
        assert!(Hash::from_str("sha256//abc").is_err());
        assert!(Hash::from_str("sha256:test").is_err());
    }

    #[test]
    fn it_computes_hashes() {
        assert_eq!(
            Sha256.hash(b"test").hex(),
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
        );

        assert_eq!(
            Sha512.hash(b"test").hex(),
            "ee26b0dd4af7e749aa1a8ee3c10ae9923f618980772e473f8819a5d4940e0db27ac185f8a0e1d5f84f88bc887fd67b143732c304cc5fa9ad8e6f57f50028a8ff"
        );
    }

    #[test]
    fn it_validates_hashes() {
        assert!(Sha256.is_valid_hash(
            &hex::decode("c22a3fb1e9de4bfa697ba258f60f14339b72c3faeb043cb75379b9ebcb2717c3")
                .unwrap()
        ));
        assert!(!Sha256.is_valid_hash(&hex::decode("c22a3f").unwrap()));
        assert!(Sha512
            .is_valid_hash(&hex::decode("d301df08cfc11928ee30b4624fbbb6aba068f06faa1c4d5e7516cf7f7b7cb36e8a38d9095ecaadef97882f093921096e9340d452b0c47e9854414e7c05e0c6c4").unwrap()));
    }
}
