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

use crate::error::Error;
use openssl::hash::MessageDigest;
use serde::{de, Deserialize, Deserializer};
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

impl<'de> Deserialize<'de> for Hash {
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where
        D: Deserializer<'de>,
    {
        let s = String::deserialize(deserializer)?;
        FromStr::from_str(&s).map_err(de::Error::custom)
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
                hasher.input(bytes);
                format!("{:x}", hasher.result())
            }
            HashType::Sha512 => {
                let mut hasher = Sha512::new();
                hasher.input(bytes);
                format!("{:x}", hasher.result())
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
