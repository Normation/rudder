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
broken
use crate::{data::node::NodeId, error::Error};
use itertools::Itertools;
use serde::Deserialize;
use std::{
    collections::HashSet,
    fmt,
    fs::read_to_string,
    net::SocketAddr,
    path::{Path, PathBuf},
    str::FromStr,
};
use toml;
use tracing::debug;

pub type BaseDirectory = PathBuf;
pub type WatchedDirectory = PathBuf;
pub type NodesListFile = PathBuf;
pub type NodesCertsFile = PathBuf;

#[derive(StructOpt, Debug)]
#[allow(clippy::module_name_repetitions)]
#[structopt(name = "rudder-relayd")]
// version and description are taken from Cargo.toml
// struct fields comments are used as option description in help
pub struct CliConfiguration {
    /// Sets a custom config directory
    #[structopt(
        short = "c",
        long = "config",
        default_value = "/opt/rudder/etc/relayd/",
        parse(from_os_str)
    )]
    pub configuration_dir: PathBuf,

    /// Checks the syntax of the configuration file
    #[structopt(short = "k", long = "check")]
    pub check_configuration: bool,
}

impl CliConfiguration {
    pub fn new<P: AsRef<Path>>(path: P, check_configuration: bool) -> Self {
        Self {
            configuration_dir: path.as_ref().to_path_buf(),
            check_configuration,
        }
    }
}

#[derive(Deserialize, Debug, PartialEq, Eq, Clone)]
// Default can be implemented in serde using the Default trait
pub struct Configuration {
    pub general: GeneralConfig,
    pub processing: ProcessingConfig,
    pub output: OutputConfig,
    pub remote_run: RemoteRun,
}

impl Configuration {
    pub fn new<P: AsRef<Path>>(path: P) -> Result<Self, Error> {
        let res = read_to_string(path.as_ref().join("main.conf"))?.parse::<Self>();
        if let Ok(ref cfg) = res {
            debug!("Parsed main configuration:\n{:#?}", &cfg);
        }
        res
    }
}

impl FromStr for Configuration {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        Ok(toml::from_str(s)?)
    }
}

#[derive(Deserialize, Debug, PartialEq, Eq, Clone)]
pub struct GeneralConfig {
    pub nodes_list_file: NodesListFile,
    pub nodes_certs_file: NodesCertsFile,
    pub node_id: NodeId,
    pub listen: SocketAddr,
    /// None means using the number of available CPUs
    pub core_threads: Option<usize>,
    pub blocking_threads: usize,
}

#[derive(Deserialize, Debug, PartialEq, Eq, Copy, Clone)]
pub struct CatchupConfig {
    pub frequency: u64,
    pub limit: u64,
}

#[derive(Deserialize, Debug, PartialEq, Eq, Clone)]
pub struct ProcessingConfig {
    pub inventory: InventoryConfig,
    pub reporting: ReportingConfig,
}

#[derive(Deserialize, Debug, PartialEq, Eq, Clone)]
pub struct InventoryConfig {
    pub directory: BaseDirectory,
    pub output: InventoryOutputSelect,
    pub catchup: CatchupConfig,
}

#[derive(Deserialize, Debug, PartialEq, Eq, Clone)]
#[serde(rename_all = "lowercase")]
pub enum InventoryOutputSelect {
    Upstream,
    Disabled,
}

#[derive(Deserialize, Debug, PartialEq, Eq, Clone)]
pub struct ReportingConfig {
    pub directory: BaseDirectory,
    pub output: ReportingOutputSelect,
    pub catchup: CatchupConfig,
    pub skip_logs: bool,
}

#[derive(Deserialize, Debug, PartialEq, Eq, Clone)]
#[serde(rename_all = "lowercase")]
pub enum ReportingOutputSelect {
    Database,
    Upstream,
    Disabled,
}

pub trait OutputSelect {
    fn is_enabled(&self) -> bool;
}

impl OutputSelect for ReportingOutputSelect {
    fn is_enabled(&self) -> bool {
        *self != ReportingOutputSelect::Disabled
    }
}

impl OutputSelect for InventoryOutputSelect {
    fn is_enabled(&self) -> bool {
        *self != InventoryOutputSelect::Disabled
    }
}

#[derive(Deserialize, Debug, PartialEq, Eq, Clone)]
pub struct RemoteRun {
    pub command: PathBuf,
}

#[derive(Deserialize, Debug, PartialEq, Eq, Clone)]
pub struct OutputConfig {
    pub database: DatabaseConfig,
    pub upstream: UpstreamConfig,
}

#[derive(Deserialize, Debug, PartialEq, Eq, Clone)]
pub struct DatabaseConfig {
    pub url: String,
    pub max_pool_size: u32,
}

#[derive(Deserialize, Debug, PartialEq, Eq, Clone)]
pub struct UpstreamConfig {
    // TODO better URL type
    pub url: String,
    pub user: String,
    pub password: String,
}

#[derive(Deserialize, Debug, PartialEq, Eq, Clone)]
pub struct LogConfig {
    pub general: LoggerConfig,
    pub filter: LogFilterConfig,
}

impl FromStr for LogConfig {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        Ok(toml::from_str(s)?)
    }
}

impl LogConfig {
    pub fn new<P: AsRef<Path>>(path: P) -> Result<Self, Error> {
        let res = read_to_string(path.as_ref().join("logging.conf"))?.parse::<Self>();
        if let Ok(ref cfg) = res {
            debug!("Parsed logging configuration:\n{:#?}", &cfg);
        }
        res
    }
}

impl fmt::Display for LogConfig {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(f, "{}{}", self.general, self.filter)
    }
}

#[derive(Copy, Debug, Eq, PartialEq, Deserialize, Clone)]
#[serde(rename_all = "lowercase")]
pub enum LogLevel {
    Error,
    Warn,
    Info,
    Debug,
    Trace,
}

impl fmt::Display for LogLevel {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(
            f,
            "{}",
            match self {
                LogLevel::Error => "error",
                LogLevel::Warn => "warn",
                LogLevel::Info => "info",
                LogLevel::Debug => "debug",
                LogLevel::Trace => "trace",
            }
        )
    }
}

#[derive(Copy, Debug, Eq, PartialEq, Deserialize, Hash, Clone)]
#[serde(rename_all = "lowercase")]
pub enum LogComponent {
    Database,
    Parser,
    Watcher,
    Statistics,
}

impl fmt::Display for LogComponent {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(
            f,
            "{}",
            match self {
                LogComponent::Database => "database",
                LogComponent::Parser => "parser",
                LogComponent::Watcher => "watcher",
                LogComponent::Statistics => "statistics",
            }
        )
    }
}

#[derive(Deserialize, Debug, PartialEq, Eq, Clone)]
pub struct LogFilterConfig {
    pub level: LogLevel,
    pub components: HashSet<LogComponent>,
    pub nodes: HashSet<NodeId>,
}

impl fmt::Display for LogFilterConfig {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(
            f,
            "{}",
            match (self.nodes.is_empty(), self.components.is_empty()) {
                (true, false) => self
                    .components
                    .iter()
                    .map(|c| format!(",[{}]={}", c, self.level))
                    .collect::<Vec<String>>()
                    .join(","),
                (false, true) => self
                    .nodes
                    .iter()
                    .map(|n| format!(",[{{node_id=\"{}\"}}]={}", n, self.level))
                    .collect::<Vec<String>>()
                    .join(","),
                // [span1{foo=1}]=error,[span2{bar=2 baz=false}],crate2[{quux=\"quuux\"}]=debug
                (false, false) => self
                    .nodes
                    .iter()
                    .flat_map(|n| self
                        .components
                        .iter()
                        .map(|c| format!(",[{}{{node_id=\"{}\"}}]={}", c, n, self.level))
                        .collect::<Vec<String>>())
                    .join(","),
                // Empty filter i.e. no filter
                (true, true) => "".to_string(),
            }
        )
    }
}

#[derive(Deserialize, Debug, PartialEq, Eq, Clone)]
pub struct LoggerConfig {
    #[serde(with = "LogLevel")]
    pub level: LogLevel,
}

impl fmt::Display for LoggerConfig {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(f, "{}", self.level)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::iter::FromIterator;

    #[test]
    fn it_generates_log_configuration() {
        let log_reference = LogConfig {
            general: LoggerConfig {
                level: LogLevel::Info,
            },
            filter: LogFilterConfig {
                level: LogLevel::Debug,
                nodes: HashSet::from_iter(vec!["root".to_string()].iter().cloned()),
                components: HashSet::from_iter(vec![LogComponent::Database].iter().cloned()),
            },
        };
        assert_eq!(
            &log_reference.to_string(),
            "info,[database{node_id=\"root\"}]=debug"
        );

        let log_reference = LogConfig {
            general: LoggerConfig {
                level: LogLevel::Info,
            },
            filter: LogFilterConfig {
                level: LogLevel::Debug,
                nodes: HashSet::new(),
                components: HashSet::new(),
            },
        };
        assert_eq!(&log_reference.to_string(), "info");

        let log_reference = LogConfig {
            general: LoggerConfig {
                level: LogLevel::Info,
            },
            filter: LogFilterConfig {
                level: LogLevel::Debug,
                nodes: HashSet::new(),
                components: HashSet::from_iter(vec![LogComponent::Database].iter().cloned()),
            },
        };
        assert_eq!(&log_reference.to_string(), "info,[database]=debug");

        let log_reference = LogConfig {
            general: LoggerConfig {
                level: LogLevel::Error,
            },
            filter: LogFilterConfig {
                level: LogLevel::Debug,
                nodes: HashSet::from_iter(vec!["root".to_string()].iter().cloned()),
                components: HashSet::new(),
            },
        };
        assert_eq!(
            &log_reference.to_string(),
            "error,[{node_id=\"root\"}]=debug"
        );
    }

    #[test]
    fn it_fails_with_configuration() {
        let empty = "";
        let config = empty.parse::<Configuration>();
        assert!(config.is_err());
    }

    #[test]
    fn it_parses_logging_configuration() {
        let log_config = LogConfig::new("tests/files/config/");
        let log_reference = LogConfig {
            general: LoggerConfig {
                level: LogLevel::Info,
            },
            filter: LogFilterConfig {
                level: LogLevel::Debug,
                nodes: HashSet::from_iter(vec!["root".to_string()].iter().cloned()),
                components: HashSet::from_iter(vec![LogComponent::Database].iter().cloned()),
            },
        };
        assert_eq!(log_config.unwrap(), log_reference);
    }

    #[test]
    fn it_parses_main_configuration() {
        let config = Configuration::new("tests/files/config/");

        let mut root_set = HashSet::new();
        root_set.insert("root".to_string());
        let reference = Configuration {
            general: GeneralConfig {
                nodes_list_file: PathBuf::from("tests/files/nodeslist.json"),
                nodes_certs_file: PathBuf::from("tests/keys/nodescerts.pem"),
                node_id: "root".to_string(),
                listen: "127.0.0.1:3030".parse().unwrap(),
                core_threads: None,
                blocking_threads: 100,
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
                    skip_logs: false,
                },
            },
            output: OutputConfig {
                upstream: UpstreamConfig {
                    url: "https://127.0.0.1:8080".to_string(),
                    user: "rudder".to_string(),
                    password: "password".to_string(),
                },
                database: DatabaseConfig {
                    url: "postgres://rudderreports:PASSWORD@127.0.0.1/rudder".to_string(),
                    max_pool_size: 5,
                },
            },
            remote_run: RemoteRun {
                command: PathBuf::from("tests/fake_agent.sh"),
            },
        };
        assert_eq!(config.unwrap(), reference);
    }
}
