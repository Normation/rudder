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
use openssl::{stack::Stack, x509::X509};
use serde::{Deserialize, Serialize};
use serde_json;
use slog::{slog_info, slog_trace, slog_warn};
use slog_scope::{info, trace, warn};
use std::collections::HashMap;
use std::fs::read_to_string;
use std::path::Path;
use std::str::FromStr;

pub type Id = String;
pub type Host = String;
pub type Cert = X509;

#[derive(Debug, Serialize, Deserialize, PartialEq, Eq)]
pub struct Info {
    pub hostname: String,
    #[serde(rename = "policy-server")]
    pub policy_server: Host,
    #[serde(rename = "key-hash")]
    pub key_hash: String,
}

pub struct NodesList {
    pub info: NodesInfo,
    // No certs on simple relays
    pub certs: Option<Certificates>,
}

impl NodesList {
    pub fn new<P: AsRef<Path>>(nodes_file: P, certificates_file: Option<P>) -> Result<Self, Error> {
        let certs = match certificates_file {
            Some(path) => Some(Certificates::new(path)?),
            None => None,
        };
        Ok(Self {
            info: NodesInfo::new(nodes_file)?,
            certs,
        })
    }

    pub fn is_subnode(&self, id: &str) -> bool {
        self.info.data.get(id).is_some()
    }

    pub fn certs(&self, id: &str) -> Option<&Stack<Cert>> {
        match &self.certs {
            Some(certs) => certs.data.get(id),
            None => {
                panic!("No certificates loaded");
            }
        }
    }
}

#[derive(Deserialize, Debug, PartialEq, Eq)]
#[serde(transparent)]
pub struct NodesInfo {
    pub data: HashMap<Id, Info>,
}

impl NodesInfo {
    pub fn new<P: AsRef<Path>>(path: P) -> Result<Self, Error> {
        info!("Parsing nodes list from {:#?}", path.as_ref());
        let nodes = read_to_string(path)?.parse::<Self>()?;
        trace!("Parsed nodes list:\n{:#?}", nodes);
        Ok(nodes)
    }
}

impl FromStr for NodesInfo {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        Ok(serde_json::from_str(s)?)
    }
}

// Certificates are stored independently as they are read from a different source
#[derive(Default)]
pub struct Certificates {
    pub data: HashMap<Id, Stack<Cert>>,
}

impl Certificates {
    pub fn new<P: AsRef<Path>>(path: P) -> Result<Self, Error> {
        info!("Parsing certs list from {:#?}", path.as_ref());
        let certs = read_to_string(path)?.parse::<Self>()?;
        Ok(certs)
    }

    fn id_from_cert(cert: &Cert) -> Result<Id, Error> {
        Ok(cert
            .subject_name()
            .entries()
            // Rudder node id uses "userId"
            .find(|c| c.object().to_string() == "userId")
            .ok_or(Error::MissingIdInCertificate)?
            .data()
            .as_utf8()?
            .to_string())
    }
}

// Read concatenated pem certs
impl FromStr for Certificates {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        let mut res = Certificates::default();
        for cert in X509::stack_from_pem(s.as_bytes())? {
            match Self::id_from_cert(&cert) {
                Ok(id) => {
                    trace!("Read certificate for node {}", id);
                    match res.data.get_mut(&id) {
                        Some(certs) => certs.push(cert)?,
                        None => {
                            let mut certs = Stack::new()?;
                            certs.push(cert)?;
                            res.data.insert(id, certs);
                        }
                    }
                }
                Err(e) => {
                    // warn and skip on certificate error
                    warn!("{}", e);
                    continue;
                }
            }
        }
        Ok(res)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_nodeslist() {
        let nodeslist = NodesInfo::new("tests/files/nodeslist.json").unwrap();
        assert_eq!(nodeslist.data["root"].hostname, "server.rudder.local");
    }

    #[test]
    fn test_parse_certs() {
        let list = Certificates::new("tests/keys/nodescerts.pem").unwrap();
        assert_eq!(list.data.len(), 2);
        assert_eq!(list.data["37817c4d-fbf7-4850-a985-50021f4e8f41"].len(), 1);
        assert_eq!(list.data["e745a140-40bc-4b86-b6dc-084488fc906b"].len(), 2);
    }
}
