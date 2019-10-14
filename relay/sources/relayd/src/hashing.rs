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
use sha2::{Digest, Sha256, Sha512};
use std::{fmt, str, str::FromStr};

#[derive(Debug, Clone, Copy)]
pub enum HashType {
    Sha256,
    Sha512,
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
    pub fn hash(self, bytes: &[u8]) -> String {
        match self {
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
        }
    }

    pub fn to_openssl_hash(self) -> MessageDigest {
        match self {
            HashType::Sha256 => MessageDigest::sha256(),
            HashType::Sha512 => MessageDigest::sha512(),
        }
    }
}
