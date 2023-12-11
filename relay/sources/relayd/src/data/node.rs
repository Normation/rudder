// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use std::{
    collections::{HashMap, HashSet},
    fs::{read, read_to_string},
    path::Path,
    str::FromStr,
};

use anyhow::{Context, Error};
use openssl::{stack::Stack, x509::X509};
use serde::{Deserialize, Deserializer};
use tracing::{debug, error, info, trace, warn};

use crate::{error::RudderError, hashing::Hash};

pub type NodeId = String;
pub type NodeIdRef = str;
pub type Host = String;

#[derive(Deserialize, Default)]
struct Info {
    hostname: Host,

    #[serde(rename = "policy-server")]
    policy_server: NodeId,

    #[serde(rename = "key-hash")]
    // If not present, use default which is `None`
    #[serde(default)]
    // If present deserialize it with `deserialize_hash`
    // Which returns an `Option`:
    // * `Some(hash)` if parsing was successful
    // * `None` in case of deserializing/parsing error which is expected to happen
    #[serde(deserialize_with = "deserialize_hash")]
    // May not exist if node keys were reset and not updated yet
    key_hash: Option<Hash>,

    #[serde(skip)]
    // Can be empty when not on a root server or no known certificates for
    // a node
    certificates: Option<Stack<X509>>,
}

// Deserialization never fails here (due to compatibility), but need a Result for serde
#[allow(clippy::unnecessary_wraps)]
fn deserialize_hash<'de, D>(deserializer: D) -> Result<Option<Hash>, D::Error>
where
    D: Deserializer<'de>,
{
    // Invalid hash likely means no hash (due to the server-side serialization using string-template)
    // Will be "sha256:" when the node has no key. Let's ignore it.
    Ok(String::deserialize(deserializer)
        .map_err(|e| debug!("could no deserialize node hash, skipping: {}", e))
        .ok()
        .and_then(|s| {
            Hash::from_str(&s)
                .map_err(|e| debug!("could no parse node hash, skipping: {}", e))
                .ok()
        }))
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

    fn add_certificate(&mut self, id: &NodeIdRef, cert: X509) -> Result<(), Error> {
        trace!("Adding certificate for node {}", id);
        self.data
            .get_mut(id)
            .ok_or_else(|| RudderError::CertificateForUnknownNode(id.to_string()))
            .map_err(|e| e.into())
            .and_then(|node| node.add_certificate(cert))
    }
}

pub struct NodesList {
    list: RawNodesList,
    my_id: NodeId,
}

impl NodesList {
    // Load nodes list from the nodeslist.json file
    pub fn new<P: AsRef<Path>>(
        my_id: NodeId,
        nodes_file: P,
        certificates_file: Option<P>,
    ) -> Result<Self, Error> {
        info!("Parsing nodes list from {:#?}", nodes_file.as_ref());

        let mut nodes = if nodes_file.as_ref().exists() {
            read_to_string(nodes_file.as_ref())
                .with_context(|| {
                    format!(
                        "Could not read nodes list from {}",
                        nodes_file.as_ref().display()
                    )
                })?
                .parse::<RawNodesList>()?
        } else {
            info!("Nodes list file does not exist, considering it as empty");
            RawNodesList::new()
        };

        if let Some(certificates_file) = certificates_file {
            if certificates_file.as_ref().exists() {
                // TODO PERF: stack_from_pem is mono threaded, could be parallelized if necessary,
                // by splitting the file before calling it
                for cert in
                    X509::stack_from_pem(&read(certificates_file.as_ref()).with_context(|| {
                        format!(
                            "Could not read certificate file from {}",
                            certificates_file.as_ref().display()
                        )
                    })?)?
                {
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

    pub fn my_id(&self) -> &NodeIdRef {
        &self.my_id
    }

    pub fn i_am_root_server(&self) -> bool {
        &self.my_id == "root"
    }

    /// Current number of sub-nodes (nodes managed by us or a downstream relay)
    pub fn sub_nodes(&self) -> usize {
        self.list.data.len()
    }

    /// Current number of managed nodes
    pub fn managed_nodes(&self) -> usize {
        self.my_neighbors().len()
    }

    /// Nodes list file only contains sub-nodes, so we only have to check for
    /// node presence.
    pub fn is_subnode(&self, id: &NodeIdRef) -> bool {
        self.list.data.get(id).is_some()
    }

    /// Get Info and fail if node is not there
    fn get(&self, id: &NodeIdRef) -> Result<&Info, Error> {
        self.list
            .data
            .get(id)
            .ok_or_else(|| RudderError::UnknownNode(id.to_string()).into())
    }

    pub fn is_my_neighbor(&self, id: &NodeIdRef) -> Result<bool, Error> {
        self.get(id).map(|n| n.policy_server == self.my_id)
    }

    /// Get key hash. Missing key is an error.
    pub fn key_hash(&self, id: &NodeIdRef) -> Result<Hash, Error> {
        self.get(id).and_then(|s| {
            s.key_hash
                .clone()
                .ok_or_else(|| RudderError::MissingKeyHashForNode(id.to_string()).into())
        })
    }

    pub fn hostname(&self, id: &NodeIdRef) -> Result<&Host, Error> {
        self.get(id).map(|s| &s.hostname)
    }

    /// Get node cert, missing cert is unexpected and an error
    pub fn certs(&self, id: &NodeIdRef) -> Result<&Stack<X509>, Error> {
        self.get(id).and_then(|node| {
            node.certificates
                .as_ref()
                .ok_or_else(|| RudderError::MissingCertificateForNode(id.to_string()).into())
        })
    }

    fn id_from_cert(cert: &X509) -> Result<NodeId, Error> {
        Ok(cert
            .subject_name()
            .entries()
            // Rudder node id uses "userId"
            .find(|c| c.object().to_string() == "userId")
            .ok_or(RudderError::MissingIdInCertificate)?
            .data()
            .as_utf8()?
            .to_string())
    }

    /// Some(Next hop) if any, None if directly connected, error if not found
    fn next_hop(&self, node_id: &NodeIdRef) -> Result<Option<NodeId>, Error> {
        // nodeslist should not contain loops but just in case
        // 20 levels of relays should be more than enough
        const MAX_RELAY_LEVELS: u8 = 20;

        if self.is_my_neighbor(node_id)? {
            return Ok(None);
        }

        let mut current_id = node_id;
        let mut current = self.get(current_id)?;
        let mut next_hop = Err(Error::msg("Next hop not found"));

        for level in 0..MAX_RELAY_LEVELS {
            if current.policy_server == self.my_id {
                next_hop = Ok(Some(current_id.to_string()));
                break;
            }
            current_id = &current.policy_server;
            current = self.get(current_id)?;

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

    pub fn my_neighbors(&self) -> Vec<Host> {
        self.list
            .data
            .values()
            .filter(|k| k.policy_server == self.my_id)
            .map(|k| k.hostname.clone())
            .collect()
    }

    pub fn neighbors_from(&self, server: &NodeIdRef, nodes: &[NodeId]) -> Vec<Host> {
        nodes
            .iter()
            .filter_map(|n| self.list.data.get::<str>(n))
            .filter(|n| n.policy_server == server)
            .map(|n| n.hostname.clone())
            .collect()
    }

    pub fn my_neighbors_from(&self, nodes: &[NodeId]) -> Vec<Host> {
        self.neighbors_from(&self.my_id, nodes)
    }

    /// Get all directly connected downstream policy servers with full information
    pub fn my_sub_relays_certs(&self) -> HashMap<NodeId, Option<&Stack<X509>>> {
        let mut relays = HashMap::new();
        for policy_server in self
            .list
            .data
            .values()
            // Extract policy servers
            .filter_map(|node| {
                let server_id = node.policy_server.clone();
                self.list
                    .data
                    .get(&server_id)
                    // (server_id, server)
                    .map(|server| (server_id, server))
            })
            // Special case for root
            // Skip if sub_relay is myself, otherwise we'll loop
            .filter(|(server_id, _)| server_id != &self.my_id)
            .filter(|(_, server)| server.policy_server == self.my_id)
            .map(|(id, server)| (id, server.certificates.as_ref()))
        {
            let _ = relays.insert(policy_server.0, policy_server.1);
        }
        relays
    }

    /// Get all directly connected downstream policy servers
    pub fn my_sub_relays(&self) -> Vec<(NodeId, Host)> {
        let mut relays = HashSet::new();
        for policy_server in self
            .list
            .data
            .values()
            // Extract policy servers
            .filter_map(|node| {
                let server_id = node.policy_server.clone();
                self.list
                    .data
                    .get(&server_id)
                    // (server_id, server)
                    .map(|server| (server_id, server))
            })
            // Special case for root
            // Skip if sub_relay is myself, otherwise we'll loop
            .filter(|(server_id, _)| server_id != &self.my_id)
            .filter(|(_, server)| server.policy_server == self.my_id)
            .map(|(id, relay)| (id, relay.hostname.clone()))
        {
            let _ = relays.insert(policy_server);
        }
        relays.into_iter().collect()
    }

    /// Relays to contact to trigger given nodes, with the matching nodes
    /// Logs and ignores unknown nodes
    pub fn my_sub_relays_from(&self, nodes: &[NodeId]) -> Vec<(NodeId, Host, Vec<NodeId>)> {
        let mut relays: HashMap<NodeId, (Host, Vec<NodeId>)> = HashMap::new();
        for node in nodes.iter() {
            let (id, host) = match self.next_hop(node) {
                // we are sure it exists
                Ok(Some(ref next_hop)) => {
                    (next_hop.clone(), self.hostname(next_hop).unwrap().clone())
                }
                Ok(None) => continue,
                Err(e) => {
                    error!("{}", e);
                    continue;
                }
            };

            if let Some(nodes) = relays.get_mut(&id) {
                nodes.1.push(node.clone());
            } else {
                relays.insert(id, (host, vec![node.clone()]));
            }
        }

        relays.into_iter().map(|(i, (h, n))| (i, h, n)).collect()
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
    use pretty_assertions::assert_eq;

    use super::*;

    #[test]
    fn it_parses_nodeslist() {
        let nodeslist =
            NodesList::new("root".to_string(), "tests/files/nodeslist.json", None).unwrap();
        assert_eq!(
            nodeslist.list.data["e745a140-40bc-4b86-b6dc-084488fc906b"].hostname,
            "node1.rudder.local"
        );
        assert_eq!(
            nodeslist.list.data["e745a140-40bc-4b86-b6dc-084488fc906b"]
                .key_hash
                .clone()
                .unwrap(),
            Hash::new(
                "sha256",
                "23cbad1561a3f8ea6aa5b880219fecf2a442e1f417c50f084558c57b45f52ee8"
            )
            .unwrap()
        );
        assert!(nodeslist.list.data["c745a140-40bc-4b86-b6dc-084488fc906b"]
            .key_hash
            .is_none());
        assert!(nodeslist.list.data["b745a140-40bc-4b86-b6dc-084488fc906b"]
            .key_hash
            .is_none());
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
            Some("tests/files/keys/nodescerts.pem"),
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
    fn if_gets_subrelays() {
        assert!(
            NodesList::new("root".to_string(), "tests/files/nodeslist.json", None)
                .unwrap()
                .is_subnode("37817c4d-fbf7-4850-a985-50021f4e8f41")
        );
        assert!(
            !NodesList::new("root".to_string(), "tests/files/nodeslist.json", None)
                .unwrap()
                .is_subnode("unknown")
        );
    }

    #[test]
    fn if_gets_my_neighbors() {
        assert!(
            NodesList::new("root".to_string(), "tests/files/nodeslist.json", None)
                .unwrap()
                .is_my_neighbor("37817c4d-fbf7-4850-a985-50021f4e8f41")
                .unwrap()
        );
        assert!(
            !NodesList::new("root".to_string(), "tests/files/nodeslist.json", None)
                .unwrap()
                .is_my_neighbor("b745a140-40bc-4b86-b6dc-084488fc906b")
                .unwrap()
        );
        assert!(
            NodesList::new("root".to_string(), "tests/files/nodeslist.json", None)
                .unwrap()
                .is_my_neighbor("unknown")
                .is_err()
        );
    }

    #[test]
    fn it_filters_neighbors() {
        let mut reference = vec!["node1.rudder.local", "localhost", "server.rudder.local"];
        reference.sort_unstable();

        let mut actual = NodesList::new("root".to_string(), "tests/files/nodeslist.json", None)
            .unwrap()
            .my_neighbors();
        actual.sort_unstable();

        assert_eq!(reference, actual);
    }

    #[test]
    fn it_gets_neighbors() {
        let mut reference = vec!["node1.rudder.local", "localhost", "server.rudder.local"];
        reference.sort_unstable();

        let mut actual = NodesList::new("root".to_string(), "tests/files/nodeslist.json", None)
            .unwrap()
            .my_neighbors();
        actual.sort_unstable();

        assert_eq!(reference, actual);
    }

    #[test]
    fn it_gets_next_hops() {
        let list = NodesList::new("root".to_string(), "tests/files/nodeslist.json", None).unwrap();

        assert_eq!(list.next_hop("root").unwrap(), None);
        assert_eq!(
            list.next_hop("a745a140-40bc-4b86-b6dc-084488fc906b")
                .unwrap(),
            Some("e745a140-40bc-4b86-b6dc-084488fc906b".to_string())
        );
        assert_eq!(
            list.next_hop("e745a140-40bc-4b86-b6dc-084488fc906b")
                .unwrap(),
            None
        );
    }

    #[test]
    fn it_gets_sub_relays() {
        let mut reference = vec![
            (
                "e745a140-40bc-4b86-b6dc-084488fc906b".to_string(),
                "node1.rudder.local".to_string(),
            ),
            (
                "37817c4d-fbf7-4850-a985-50021f4e8f41".to_string(),
                "localhost".to_string(),
            ),
        ];
        reference.sort_unstable();

        let mut actual = NodesList::new("root".to_string(), "tests/files/nodeslist.json", None)
            .unwrap()
            .my_sub_relays();
        actual.sort_unstable();

        assert_eq!(reference, actual);
    }

    #[test]
    fn it_filters_sub_relays() {
        let mut reference = vec![(
            "e745a140-40bc-4b86-b6dc-084488fc906b".to_string(),
            "node1.rudder.local".to_string(),
            vec![
                "b745a140-40bc-4b86-b6dc-084488fc906b".to_string(),
                "a745a140-40bc-4b86-b6dc-084488fc906b".to_string(),
            ],
        )];
        reference.sort();

        let mut actual = NodesList::new("root".to_string(), "tests/files/nodeslist.json", None)
            .unwrap()
            .my_sub_relays_from(&[
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
