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
use serde::Deserialize;
use serde_json;
use std::{
    collections::HashMap,
    fs::{read, read_to_string},
    path::Path,
    str::FromStr,
};
use tracing::{info, trace, warn};

use crate::remote_run::RemoteRunTarget;
use itertools::Itertools;

pub type NodeId = String;
pub type Host = String;
pub type KeyHash = String;

// We ignore the key-hash field as we directly deal with proper certificates
#[derive(Deserialize, Default)]
struct Info {
    // hostname and policy_server be used for remote-run
    // TODO: remove annotation once it's done
    #[allow(dead_code)]
    hostname: Host,
    #[serde(rename = "policy-server")]
    #[allow(dead_code)]
    policy_server: NodeId,
    #[serde(rename = "key-hash")]
    #[allow(dead_code)]
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
        let mut nodes = read_to_string(nodes_file)?.parse::<RawNodesList>()?;

        if let Some(certificates_file) = certificates_file {
            for cert in X509::stack_from_pem(&read(certificates_file.as_ref())?)? {
                Self::id_from_cert(&cert)
                    .and_then(|id| nodes.add_certificate(&id, cert))
                    .map_err(|e| warn!("{}", e))
                    // Skip node and continue
                    .unwrap_or(())
            }
        }
        Ok(NodesList { list: nodes, my_id })
    }

    /// Nodes list file only contains sub-nodes, so we only have to check for
    /// node presence.
    pub fn is_subnode(&self, id: &str) -> bool {
        self.list.data.get(id).is_some()
    }

    pub fn get_keyhash_from_uuid(&self, source_uuid: &str) -> Option<String> {
        self.list.data.get(source_uuid).map(|s| s.key_hash.clone())
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

    fn get_neighbors(&self) -> Vec<String> {
        self.list
            .data
            .values()
            .filter(|k| k.policy_server == self.my_id)
            .map(|k| k.hostname.clone())
            .collect()
    }

    fn get_relays(&self) -> Vec<String> {
        self.list
            .data
            .values()
            .map(|v| v.policy_server.clone())
            .unique()
            .collect()
    }

    fn get_all_subnodes(&self) -> Vec<String> {
        // retrieve all the nodes under a relay
        self.list.data.keys().map(|v| v.clone()).collect()
    }

    pub fn get_neighbors_from_target(&self, target_nodes: RemoteRunTarget) -> Vec<String> {
        match target_nodes {
            RemoteRunTarget::All => self.get_neighbors(),
            RemoteRunTarget::Nodes(nodes) => self
                .list
                .data
                .values()
                .map(|k| k.hostname.clone())
                .filter(|k| nodes.contains(&k.to_string()))
                .collect(),
        }
    }

    fn get_neighbors_which_are_relay(&self, relay_id: &str) -> Vec<String> {
        self.list
            .data
            .values()
            .map(|v| v.policy_server.clone())
            .unique()
            .filter(|k| k == relay_id)
            .collect()
    }
}

impl FromStr for RawNodesList {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        Ok(serde_json::from_str(s)?)
    }
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
    fn its_filtering_nodes_requests() {
        let has_to_be_filtered: RemoteRunTarget = RemoteRunTarget::Nodes(vec![
            "37817c4d-fbf7-4850-a985-50021f4e8f41".to_string(),
            "b745a140-40bc-4b86-b6dc-084488fc906b".to_string(),
            "a745a140-40bc-4b86-b6dc-084488fc906b".to_string(),
            "j745a140-40bc-4b86-b6dc-084488fc906b".to_string(),
            "root".to_string(),
        ]); // j doit etre filtré

        let mut has_been_filtered = [
            "37817c4d-fbf7-4850-a985-50021f4e8f41",
            "b745a140-40bc-4b86-b6dc-084488fc906b",
            "a745a140-40bc-4b86-b6dc-084488fc906b",
            "root",
        ];

        let all_my_nodes: RemoteRunTarget = RemoteRunTarget::All;

        let mut all_my_nodes_for_real = [
            "37817c4d-fbf7-4850-a985-50021f4e8f41",
            "b745a140-40bc-4b86-b6dc-084488fc906b",
            "c745a140-40bc-4b86-b6dc-084488fc906b",
            "e745a140-40bc-4b86-b6dc-084488fc906b",
            "a745a140-40bc-4b86-b6dc-084488fc906b",
            "root",
        ];

        let file_nodelist =
            NodesList::new("root".to_string(), "tests/files/nodeslist.json", None).unwrap();

        assert_eq!(
            file_nodelist
                .get_neighbors_from_target(has_to_be_filtered)
                .sort(),
            has_been_filtered.sort()
        );

        assert_eq!(
            file_nodelist
                .get_neighbors_from_target(all_my_nodes)
                .sort(),
            all_my_nodes_for_real.sort()
        );
    }

    #[test]
    fn its_getting_all_the_subnodes() {
        let mut my_string_vec = [
            "37817c4d-fbf7-4850-a985-50021f4e8f41",
            "b745a140-40bc-4b86-b6dc-084488fc906b",
            "c745a140-40bc-4b86-b6dc-084488fc906b",
            "e745a140-40bc-4b86-b6dc-084488fc906b",
            "a745a140-40bc-4b86-b6dc-084488fc906b",
            "root",
        ];

        let file_nodelist =
            NodesList::new("root".to_string(), "tests/files/nodeslist.json", None).unwrap();

        assert_eq!(
            file_nodelist.get_all_subnodes().sort(),
            my_string_vec.sort()
        );
    }

    #[test]
    fn its_getting_the_neighbors() {
        let mut my_string_vec = [
            "e745a140-40bc-4b86-b6dc-084488fc906b",
            "root",
            "37817c4d-fbf7-4850-a985-50021f4e8f41",
        ];

        let file_nodelist =
            NodesList::new("root".to_string(), "tests/files/nodeslist.json", None).unwrap();

        assert_eq!(file_nodelist.get_neighbors().sort(), my_string_vec.sort());
    }

    #[test]
    fn its_getting_the_relays() {
        let mut my_string_vec = [
            "e745a140-40bc-4b86-b6dc-084488fc906b",
            "root",
            "37817c4d-fbf7-4850-a985-50021f4e8f41",
            "a",
        ];

        let file_nodelist =
            NodesList::new("root".to_string(), "tests/files/nodeslist.json", None).unwrap();

        assert_eq!(file_nodelist.get_relays().sort(), my_string_vec.sort());
    }

    #[test]
    fn its_getting_the_neighbors_which_are_relay() {
        let mut my_string_vec = [
            "e745a140-40bc-4b86-b6dc-084488fc906b",
            "root",
            "37817c4d-fbf7-4850-a985-50021f4e8f41",
        ];

        let file_nodelist =
            NodesList::new("root".to_string(), "tests/files/nodeslist.json", None).unwrap();

        assert_eq!(
            file_nodelist.get_neighbors_which_are_relay("root").sort(),
            my_string_vec.sort()
        );
    }
}
