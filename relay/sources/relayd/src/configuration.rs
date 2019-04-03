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

use crate::{data::node, error::Error};
use serde::Deserialize;
use slog;
use slog::{Key, Level, Record, Serializer, Value};
use std::fmt;
use std::fmt::Display;
use std::fs::read_to_string;
use std::str::FromStr;
use std::{
    collections::HashSet,
    net::SocketAddr,
    path::{Path, PathBuf},
};
use toml;

pub type BaseDirectory = PathBuf;
pub type WatchedDirectory = PathBuf;
pub type NodesListFile = PathBuf;

#[derive(Debug)]
#[allow(clippy::module_name_repetitions)]
pub struct CliConfiguration {
    pub configuration_file: PathBuf,
    pub check_configuration: bool,
}

impl CliConfiguration {
    pub fn new<P: AsRef<Path>>(path: P, check_configuration: bool) -> Self {
        Self {
            configuration_file: path.as_ref().to_path_buf(),
            check_configuration,
        }
    }
}

#[derive(Deserialize, Debug, PartialEq, Eq)]
// Default can be implemented in serde using the Default trait
pub struct Configuration {
    pub general: GeneralConfig,
    pub processing: ProcessingConfig,
    pub output: OutputConfig,
    pub logging: LogConfig,
}

impl Configuration {
    pub fn new<P: AsRef<Path>>(path: P) -> Result<Self, Error> {
        read_to_string(path)?.parse::<Self>()
    }
}

impl FromStr for Configuration {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        Ok(toml::from_str(s)?)
    }
}

#[derive(Deserialize, Debug, PartialEq, Eq)]
pub struct GeneralConfig {
    pub nodes_list_file: NodesListFile,
    pub node_id: node::Id,
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
    pub filter: LogFilterConfig,
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

#[derive(Copy, Clone, Debug, Eq, PartialEq, Deserialize, Hash)]
#[serde(rename_all = "lowercase")]
pub enum LogComponent {
    Database,
    Parser,
    Watcher,
    Statistics,
}

impl LogComponent {
    pub fn tag(self) -> &'static str {
        match self {
            LogComponent::Database => "database",
            LogComponent::Parser => "parser",
            LogComponent::Watcher => "watcher",
            LogComponent::Statistics => "statistics",
        }
    }
}

impl Display for LogComponent {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(f, "{}", self.tag())
    }
}

impl Value for LogComponent {
    fn serialize(&self, _record: &Record, key: Key, serializer: &mut Serializer) -> slog::Result {
        serializer.emit_str(key, self.tag())
    }
}

#[derive(Deserialize, Debug, PartialEq, Eq)]
pub struct LogFilterConfig {
    #[serde(with = "LogLevel")]
    pub level: Level,
    pub components: HashSet<LogComponent>,
    pub nodes: HashSet<node::Id>,
}

#[derive(Deserialize, Debug, PartialEq, Eq)]
pub struct LoggerConfig {
    #[serde(with = "LogLevel")]
    pub level: Level,
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::iter::FromIterator;

    #[test]
    fn test_empty_configuration() {
        let empty = "";
        let config = empty.parse::<Configuration>();
        assert!(config.is_err());
    }

    #[test]
    fn test_configuration() {
        let config = Configuration::new("tests/files/relayd.toml");

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
                    directory: PathBuf::from("target/tmp/inventories/"),
                    output: InventoryOutputSelect::Upstream,
                    catchup: CatchupConfig {
                        frequency: 10,
                        limit: 50,
                    },
                },
                reporting: ReportingConfig {
                    directory: PathBuf::from("target/tmp/runlogs/"),
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
                general: LoggerConfig { level: Level::Info },
                filter: LogFilterConfig {
                    level: Level::Debug,
                    nodes: HashSet::from_iter(vec!["root".to_string()].iter().cloned()),
                    components: HashSet::from_iter(vec![LogComponent::Database].iter().cloned()),
                },
            },
        };
        assert_eq!(config.unwrap(), reference);
    }
}
