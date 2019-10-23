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
use std::{
    collections::{HashMap, HashSet},
    fs::{read, read_to_string},
    path::Path,
    str::FromStr,
};
use tracing::{info, trace, warn};

pub type NodeId = String;
pub type Host = String;
pub type KeyHash = String;

// We ignore the key-hash field as we directly deal with proper certificates
#[derive(Deserialize, Default)]
struct Info {
    hostname: Host,
    #[serde(rename = "policy-server")]
    policy_server: NodeId,
    #[serde(rename = "key-hash")]
    key_hash: KeyHash,
    #[serde(skip)]
    // Can be empty when not on a root server or no known certificates for
    // a node
    certificates: Option<Stack<X509>>,
}

impl Info {
    fn add_certificate(&mut self, cert: X509) -> Result<(), Error> {
        match self.certificates {
            Some(ref mut certs) => certs.push(cert)?,
            None => {
                let mut certs = Stack::new()?;
                certs.push(cert)?;
                self.certificates = Some(certs);
            }
        }
        Ok(())
    }
}

#[derive(Deserialize)]
#[serde(transparent)]
pub struct RawNodesList {
    data: HashMap<NodeId, Info>,
}

impl RawNodesList {
    fn new() -> Self {
        Self {
            data: HashMap::new(),
        }
    }

    fn add_certificate(&mut self, id: &str, cert: X509) -> Result<(), Error> {
        trace!("Adding certificate for node {}", id);
        self.data
            .get_mut(id)
            .ok_or_else(|| Error::CertificateForUnknownNode(id.to_string()))
            .and_then(|node| node.add_certificate(cert))
    }
}

pub struct NodesList {
    list: RawNodesList,
    my_id: String,
}

impl NodesList {
    // Load nodes list from the nodeslist.json file
    pub fn new<P: AsRef<Path>>(
        my_id: String,
        nodes_file: P,
        certificates_file: Option<P>,
    ) -> Result<Self, Error> {
        info!("Parsing nodes list from {:#?}", nodes_file.as_ref());

        let mut nodes = if nodes_file.as_ref().exists() {
            read_to_string(nodes_file)?.parse::<RawNodesList>()?
        } else {
            info!("Nodes list file does not exist, considering it as empty");
            RawNodesList::new()
        };

        if let Some(certificates_file) = certificates_file {
            if certificates_file.as_ref().exists() {
                // TODO PERF: stack_from_pem is mono threaded, could be parallelized if necessary,
                // by splitting the file before calling it
                for cert in X509::stack_from_pem(&read(certificates_file.as_ref())?)? {
                    Self::id_from_cert(&cert)
                        .and_then(|id| nodes.add_certificate(&id, cert))
                        .map_err(|e| warn!("{}", e))
                        // Skip node and continue
                        .unwrap_or(())
                }
            } else {
                info!("Certificates file does not exist, skipping");
            }
        }
        Ok(NodesList { list: nodes, my_id })
    }

    pub fn counts(&self) -> NodeCounts {
        NodeCounts {
            sub_nodes: self.list.data.len(),
            managed_nodes: self.neighbors().len(),
        }
    }

    /// Nodes list file only contains sub-nodes, so we only have to check for
    /// node presence.
    pub fn is_subnode(&self, id: &str) -> bool {
        self.list.data.get(id).is_some()
    }

    pub fn key_hash(&self, id: &str) -> Option<KeyHash> {
        self.list.data.get(id).map(|s| s.key_hash.clone())
    }

    pub fn hostname(&self, id: &str) -> Option<Host> {
        self.list.data.get(id).map(|s| s.hostname.clone())
    }

    pub fn certs(&self, id: &str) -> Option<&Stack<X509>> {
        self.list
            .data
            .get(id)
            .and_then(|node| node.certificates.as_ref())
    }

    fn id_from_cert(cert: &X509) -> Result<NodeId, Error> {
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

    fn next_hop(&self, node_id: &str) -> Option<Host> {
        // nodeslist should not contain loops but just in case
        // 20 levels of relays should be more than enough
        const MAX_RELAY_LEVELS: u8 = 20;

        let mut current_id = node_id;
        let mut current = self.list.data.get(current_id)?;
        let mut next_hop: Option<NodeId> = None;

        for level in 0..MAX_RELAY_LEVELS {
            if current.policy_server == self.my_id {
                next_hop = Some(current_id.to_string());
                break;
            }
            current_id = &current.policy_server;
            current = self.list.data.get(current_id)?;

            if level == MAX_RELAY_LEVELS {
                warn!(
                    "Reached maximum level of relay ({}) for {}, there is probably a loop",
                    MAX_RELAY_LEVELS, node_id
                );
            }
        }

        next_hop
    }

    // NOTE: Following methods could be made faster by pre-computing a graph in cache

    pub fn neighbors(&self) -> Vec<Host> {
        self.list
            .data
            .values()
            .filter(|k| k.policy_server == self.my_id)
            .map(|k| k.hostname.clone())
            .collect()
    }

    pub fn neighbors_from(&self, nodes: &[String]) -> Vec<Host> {
        nodes
            .iter()
            .filter_map(|n| self.list.data.get::<str>(n))
            .filter(|n| n.policy_server == self.my_id)
            .map(|n| n.hostname.clone())
            .collect()
    }

    pub fn sub_relays(&self) -> Vec<Host> {
        let mut relays = HashSet::new();
        for policy_server in self
            .list
            .data
            .values()
            .filter_map(|v| self.list.data.get(&v.policy_server))
            .filter(|v| v.policy_server == self.my_id)
            .map(|v| v.hostname.clone())
        {
            let _ = relays.insert(policy_server);
        }
        relays.into_iter().collect()
    }

    pub fn sub_relays_from(&self, nodes: &[String]) -> Vec<Host> {
        let mut relays = HashSet::new();
        for relay in nodes
            .iter()
            .filter_map(|n| self.next_hop(n))
            .filter(|n| n != &self.my_id)
        {
            let _ = relays.insert(
                self.list
                    .data
                    .get::<str>(&relay)
                    .map(|n| n.hostname.clone())
                    .unwrap(),
            );
        }
        relays.into_iter().collect()
    }
}

impl FromStr for RawNodesList {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        Ok(serde_json::from_str(s)?)
    }
}

#[derive(Serialize, Debug, PartialEq, Eq)]
pub struct NodeCounts {
    // Total nodes under this relays
    pub sub_nodes: usize,
    // Nodes directly managed by this relay
    pub managed_nodes: usize,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn it_parses_nodeslist() {
        let nodeslist =
            NodesList::new("root".to_string(), "tests/files/nodeslist.json", None).unwrap();
        assert_eq!(
            nodeslist.list.data["e745a140-40bc-4b86-b6dc-084488fc906b"].hostname,
            "node1.rudder.local"
        );
        assert_eq!(nodeslist.list.data.len(), 6);
    }

    #[test]
    fn it_parses_absent_nodeslist() {
        let nodeslist =
            NodesList::new("root".to_string(), "tests/files/notthere.json", None).unwrap();
        assert_eq!(nodeslist.list.data.len(), 0);
    }

    #[test]
    fn it_parses_big_nodeslist() {
        assert!(NodesList::new(
            "root".to_string(),
            "benches/files/nodeslist.json",
            Some("benches/files/allnodescerts.pem")
        )
        .is_ok())
    }

    #[test]
    fn it_parses_certificates() {
        let nodeslist = NodesList::new(
            "root".to_string(),
            "tests/files/nodeslist.json",
            Some("tests/keys/nodescerts.pem"),
        )
        .unwrap();
        assert_eq!(nodeslist.list.data.len(), 6);
        assert_eq!(
            nodeslist.list.data["37817c4d-fbf7-4850-a985-50021f4e8f41"]
                .certificates
                .as_ref()
                .unwrap()
                .len(),
            1
        );
        assert_eq!(
            nodeslist.list.data["e745a140-40bc-4b86-b6dc-084488fc906b"]
                .certificates
                .as_ref()
                .unwrap()
                .len(),
            2
        );
    }

    #[test]
    fn it_filters_neighbors() {
        let mut reference = vec![
            "node1.rudder.local",
            "node2.rudder.local",
            "server.rudder.local",
        ];
        reference.sort();

        let mut actual = NodesList::new("root".to_string(), "tests/files/nodeslist.json", None)
            .unwrap()
            .neighbors();
        actual.sort();

        assert_eq!(reference, actual);
    }

    #[test]
    fn it_gets_neighbors() {
        let mut reference = vec![
            "node1.rudder.local",
            "node2.rudder.local",
            "server.rudder.local",
        ];
        reference.sort();

        let mut actual = NodesList::new("root".to_string(), "tests/files/nodeslist.json", None)
            .unwrap()
            .neighbors();
        actual.sort();

        assert_eq!(reference, actual);
    }

    #[test]
    fn it_gets_sub_relays() {
        let mut reference = vec![
            "node1.rudder.local",
            "node2.rudder.local",
            "server.rudder.local",
        ];
        reference.sort();

        let mut actual = NodesList::new("root".to_string(), "tests/files/nodeslist.json", None)
            .unwrap()
            .sub_relays();
        actual.sort();

        assert_eq!(reference, actual);
    }

    #[test]
    fn it_filters_sub_relays() {
        let mut reference = vec!["node1.rudder.local", "node2.rudder.local"];
        reference.sort();

        let mut actual = NodesList::new("root".to_string(), "tests/files/nodeslist.json", None)
            .unwrap()
            .sub_relays_from(&[
                "b745a140-40bc-4b86-b6dc-084488fc906b".to_string(),
                "a745a140-40bc-4b86-b6dc-084488fc906b".to_string(),
                "root".to_string(),
                "37817c4d-fbf7-4850-a985-50021f4e8f41".to_string(),
                "e745a140-40bc-4b86-b6dc-084488fc906b".to_string(),
            ]);
        actual.sort();

        assert_eq!(reference, actual);
    }
}
