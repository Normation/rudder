// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use crate::error::Error;
use openssl::hash::MessageDigest;
use sha2::{Digest, Sha256, Sha512};
use std::{fmt, str, str::FromStr};

#[derive(Debug, Clone, PartialEq, Eq, Default)]

pub struct Hash {
    pub hash_type: HashType,
    pub value: String,
}

impl Hash {
    pub fn new(hash_type: String, value: String) -> Result<Hash, Error> {
        HashType::from_str(&hash_type).and_then(|t| Hash::new_with_type(t, value))
    }

    pub fn new_with_type(hash_type: HashType, value: String) -> Result<Hash, Error> {
        if hash_type.is_valid_hash(&value) {
            Ok(Hash { hash_type, value })
        } else {
            Err(Error::InvalidHash(value))
        }
    }
}

impl FromStr for Hash {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        let parts = s.split(':').collect::<Vec<&str>>();
        if parts.len() == 2 {
            let hash_type = parts[0].parse::<HashType>()?;

            if hash_type.is_valid_hash(parts[1]) {
                Ok(Hash {
                    hash_type,
                    value: parts[1].to_string(),
                })
            } else {
                Err(Error::InvalidHash(s.to_string()))
            }
        } else {
            Err(Error::InvalidHash(s.to_string()))
        }
    }
}

impl fmt::Display for Hash {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(f, "{}:{}", self.hash_type, self.value,)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HashType {
    Sha256,
    Sha512,
}

impl Default for HashType {
    fn default() -> Self {
        HashType::Sha512
    }
}

impl FromStr for HashType {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        match s {
            "sha256" => Ok(HashType::Sha256),
            "sha512" => Ok(HashType::Sha512),
            _ => Err(Error::InvalidHashType {
                invalid: s.to_string(),
                valid: "sha256, sha512",
            }),
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
                format!("{:x}", hasher.finalize())
            }
            HashType::Sha512 => {
                let mut hasher = Sha512::new();
                hasher.update(bytes);
                format!("{:x}", hasher.finalize())
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
    pub fn is_valid_hash(self, hash: &str) -> bool {
        hex::decode(hash)
            .map(|hash| hash.len() == self.size_bytes())
            .unwrap_or(false)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn it_parses_hash_types() {
        assert_eq!(HashType::from_str("sha256").unwrap(), HashType::Sha256);
        assert_eq!(HashType::from_str("sha512").unwrap(), HashType::Sha512);
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
                hash_type: HashType::Sha256,
                value: "c22a3fb1e9de4bfa697ba258f60f14339b72c3faeb043cb75379b9ebcb2717c3"
                    .to_string()
            }
        );
        assert_eq!(
            Hash::from_str(
                "sha512:d301df08cfc11928ee30b4624fbbb6aba068f06faa1c4d5e7516cf7f7b7cb36e8a38d9095ecaadef97882f093921096e9340d452b0c47e9854414e7c05e0c6c4"
            )
            .unwrap(),
            Hash {
                hash_type: HashType::Sha512,
                value: "d301df08cfc11928ee30b4624fbbb6aba068f06faa1c4d5e7516cf7f7b7cb36e8a38d9095ecaadef97882f093921096e9340d452b0c47e9854414e7c05e0c6c4"
                    .to_string()
            }
        );
        assert!(Hash::from_str("").is_err());
        assert!(Hash::from_str("sha256:").is_err());
        assert!(Hash::from_str("sha256:test").is_err());
    }

    #[test]
    fn it_computes_hashes() {
        let sha256 = HashType::Sha256;
        assert_eq!(
            sha256.hash("test".as_bytes()).value,
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
        );

        let sha512 = HashType::Sha512;
        assert_eq!(sha512.hash("test".as_bytes()).value, "ee26b0dd4af7e749aa1a8ee3c10ae9923f618980772e473f8819a5d4940e0db27ac185f8a0e1d5f84f88bc887fd67b143732c304cc5fa9ad8e6f57f50028a8ff");
    }

    #[test]
    fn it_validates_hashes() {
        let sha256 = HashType::Sha256;
        assert!(sha256
            .is_valid_hash("c22a3fb1e9de4bfa697ba258f60f14339b72c3faeb043cb75379b9ebcb2717c3"));
        assert!(!sha256
            .is_valid_hash("é22a3fb1e9de4bfa697ba258f60f14339b72c3faeb043cb75379b9ebcb2717c3"));
        assert!(!sha256.is_valid_hash("c22a3f"));
        let sha512 = HashType::Sha512;
        assert!(sha512
            .is_valid_hash("d301df08cfc11928ee30b4624fbbb6aba068f06faa1c4d5e7516cf7f7b7cb36e8a38d9095ecaadef97882f093921096e9340d452b0c47e9854414e7c05e0c6c4"));
        assert!(!sha512
            .is_valid_hash("{301df08cfc11928ee30b4624fbbb6aba068f06faa1c4d5e7516cf7f7b7cb36e8a38d9095ecaadef97882f093921096e9340d452b0c47e9854414e7c05e0c6c4"));
        assert!(!sha512.is_valid_hash("test"));
    }
}
