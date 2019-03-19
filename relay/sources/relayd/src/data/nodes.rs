use crate::error::Error;
use serde::{Deserialize, Serialize};
use serde_json;
use std::collections::HashMap;

pub type NodeId = String;
pub type NodeHost = String;

#[derive(Debug, Serialize, Deserialize)]
pub struct NodeInfo {
    pub hostname: String,
    #[serde(rename = "policy-server")]
    pub policy_server: NodeHost,
    #[serde(rename = "key-hash")]
    pub key_hash: String,
}

pub type NodesList = HashMap<NodeId, NodeInfo>;

pub fn parse_nodeslist(s: &str) -> Result<NodesList, Error> {
    Ok(serde_json::from_str(s)?)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs::read_to_string;

    #[test]
    fn test_parse_nodeslist() {
        let list = read_to_string("tests/nodeslist.json").unwrap();
        let nodeslist = parse_nodeslist(&list).unwrap();
        assert_eq!(nodeslist["root"].hostname, "server.rudder.local");
    }
}
