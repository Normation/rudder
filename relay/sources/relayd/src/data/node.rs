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
use slog::{slog_info, slog_trace};
use slog_scope::{info, trace};
use std::collections::HashMap;
use std::fs::read_to_string;
use std::path::Path;
use std::str::FromStr;

pub type Id = String;
pub type Host = String;

#[derive(Debug, Serialize, Deserialize, PartialEq, Eq)]
pub struct Info {
    pub hostname: String,
    #[serde(rename = "policy-server")]
    pub policy_server: Host,
    #[serde(rename = "key-hash")]
    pub key_hash: String,
}

#[derive(Deserialize, Debug, PartialEq, Eq)]
#[serde(transparent)]
pub struct List {
    pub data: HashMap<Id, Info>,
}

impl List {
    pub fn new<P: AsRef<Path>>(path: P) -> Result<Self, Error> {
        info!("Parsing nodes list from {:#?}", path.as_ref());
        let nodes = read_to_string(path)?.parse::<Self>()?;
        trace!("Parsed nodes list:\n{:#?}", nodes);
        Ok(nodes)
    }
}

impl FromStr for List {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        Ok(serde_json::from_str(s)?)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_nodeslist() {
        let nodeslist = List::new("tests/files/nodeslist.json").unwrap();
        assert_eq!(nodeslist.data["root"].hostname, "server.rudder.local");
    }
}
