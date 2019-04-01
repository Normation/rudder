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
        let list = read_to_string("tests/files/nodeslist.json").unwrap();
        let nodeslist = parse_nodeslist(&list).unwrap();
        assert_eq!(nodeslist["root"].hostname, "server.rudder.local");
    }
}
