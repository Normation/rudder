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

use crate::{data::nodes::NodeId, error::Error};
use serde::Deserialize;
use slog::Level;
use std::net::SocketAddr;
use std::{collections::HashSet, path::PathBuf};
use toml;

pub const DEFAULT_CONFIGURATION_FILE: &str = "/opt/rudder/etc/relayd.conf";

pub type BaseDirectory = PathBuf;
pub type WatchedDirectory = PathBuf;
pub type NodesListFile = PathBuf;

#[derive(Deserialize, Debug, PartialEq, Eq)]
// Default can be implemented in serde using the Default trait
pub struct Configuration {
    pub general: GeneralConfig,
    pub processing: ProcessingConfig,
    pub output: OutputConfig,
    pub logging: LogConfig,
}

impl Configuration {
    pub fn read_configuration(configuration: &str) -> Result<Configuration, Error> {
        let cfg = toml::from_str(configuration)?;
        Ok(cfg)
    }
}

#[derive(Deserialize, Debug, PartialEq, Eq)]
pub struct GeneralConfig {
    pub nodes_list_file: NodesListFile,
    pub node_id: NodeId,
    pub listen: SocketAddr,
}

#[derive(Deserialize, Debug, PartialEq, Eq, Copy, Clone)]
pub struct CatchupConfig {
    pub frequency: u64,
    pub limit: u64,
}

#[derive(Deserialize, Debug, PartialEq, Eq)]
pub struct ProcessingConfig {
    pub inventory: InventoryConfig,
    pub reporting: ReportingConfig,
}

#[derive(Deserialize, Debug, PartialEq, Eq)]
pub struct InventoryConfig {
    pub directory: BaseDirectory,
    pub output: InventoryOutputSelect,
    pub catchup: CatchupConfig,
}

#[derive(Deserialize, Debug, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum InventoryOutputSelect {
    Upstream,
    Disabled,
}

#[derive(Deserialize, Debug, PartialEq, Eq)]
pub struct ReportingConfig {
    pub directory: BaseDirectory,
    pub output: ReportingOutputSelect,
    pub catchup: CatchupConfig,
}

#[derive(Deserialize, Debug, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum ReportingOutputSelect {
    Database,
    Upstream,
    Disabled,
}

#[derive(Deserialize, Debug, PartialEq, Eq)]
pub struct OutputConfig {
    pub database: DatabaseConfig,
    pub upstream: UpstreamConfig,
}

#[derive(Deserialize, Debug, PartialEq, Eq)]
pub struct DatabaseConfig {
    pub url: String,
    pub max_pool_size: u32,
}

#[derive(Deserialize, Debug, PartialEq, Eq)]
pub struct UpstreamConfig {
    pub url: String,
}

#[derive(Deserialize, Debug, PartialEq, Eq)]
pub struct LogConfig {
    pub general: LoggerConfig,
}

#[serde(remote = "Level")]
#[derive(Copy, Clone, Debug, Eq, PartialEq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum LogLevel {
    Critical,
    Error,
    Warning,
    Info,
    Debug,
    Trace,
}

#[derive(Deserialize, Debug, PartialEq, Eq)]
pub struct LogFilterConfig {
    #[serde(with = "LogLevel")]
    pub level: Level,
    pub nodes: HashSet<NodeId>,
}

#[derive(Deserialize, Debug, PartialEq, Eq)]
pub struct LoggerConfig {
    #[serde(with = "LogLevel")]
    pub level: Level,
    pub filter: LogFilterConfig,
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs::read_to_string;

    #[test]
    fn test_empty_configuration() {
        let empty = "";
        let config = Configuration::read_configuration(empty);
        assert!(config.is_err());
    }

    #[test]
    fn test_configuration() {
        let config =
            Configuration::read_configuration(&read_to_string("tests/files/relayd.conf").unwrap());

        let mut root_set = HashSet::new();
        root_set.insert("root".to_string());
        let reference = Configuration {
            general: GeneralConfig {
                nodes_list_file: PathBuf::from("tests/files/nodeslist.json"),
                node_id: "root".to_string(),
                listen: "127.0.0.1:3030".parse().unwrap(),
            },
            processing: ProcessingConfig {
                inventory: InventoryConfig {
                    directory: PathBuf::from("tests/tmp/inventories/"),
                    output: InventoryOutputSelect::Upstream,
                    catchup: CatchupConfig {
                        frequency: 10,
                        limit: 50,
                    },
                },
                reporting: ReportingConfig {
                    directory: PathBuf::from("tests/tmp/runlogs/"),
                    output: ReportingOutputSelect::Database,
                    catchup: CatchupConfig {
                        frequency: 10,
                        limit: 50,
                    },
                },
            },
            output: OutputConfig {
                upstream: UpstreamConfig {
                    url: "https://127.0.0.1:8080".to_string(),
                },
                database: DatabaseConfig {
                    url: "postgres://rudderreports:PASSWORD@127.0.0.1/rudder".to_string(),
                    max_pool_size: 5,
                },
            },
            logging: LogConfig {
                general: LoggerConfig {
                    level: Level::Info,
                    filter: LogFilterConfig {
                        level: Level::Trace,
                        nodes: HashSet::new(),
                    },
                },
            },
        };
        assert_eq!(config.unwrap(), reference);
    }
}
