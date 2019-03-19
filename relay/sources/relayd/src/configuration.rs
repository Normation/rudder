use crate::{data::nodes::NodeId, error::Error};
use serde::Deserialize;
use slog::slog_debug;
use slog::Level;
use slog_scope::debug;
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
        debug!("Read configuration:\n{:#?}", cfg);
        Ok(cfg)
    }
}

#[derive(Deserialize, Debug, PartialEq, Eq)]
pub struct GeneralConfig {
    pub nodes_list_file: NodesListFile,
    pub node_id: NodeId,
    pub listen: SocketAddr,
}

#[derive(Deserialize, Debug, PartialEq, Eq)]
pub struct CatchupConfig {
    pub frequency: u32,
    pub limit: u32,
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
    //    pub performance: LoggerConfig,
    //    pub parsing: LoggerConfig,
    //    pub processing: LoggerConfig,
}

#[serde(remote = "Level")]
#[derive(Copy, Clone, Debug, Eq, PartialEq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum LogLevel {
    /// Critical
    Critical,
    /// Error
    Error,
    /// Warning
    Warning,
    /// Info
    Info,
    /// Debug
    Debug,
    /// Trace
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
            Configuration::read_configuration(&read_to_string("tests/relayd.conf").unwrap());

        let mut root_set = HashSet::new();
        root_set.insert("root".to_string());
        let reference = Configuration {
            general: GeneralConfig {
                nodes_list_file: PathBuf::from("tests/nodeslist.json"),
                node_id: "root".to_string(),
                listen: "127.0.0.1:3030".parse().unwrap(),
            },
            processing: ProcessingConfig {
                inventory: InventoryConfig {
                    directory: PathBuf::from("tests/inventories/"),
                    output: InventoryOutputSelect::Upstream,
                    catchup: CatchupConfig {
                        frequency: 10,
                        limit: 50,
                    },
                },
                reporting: ReportingConfig {
                    directory: PathBuf::from("tests/runlogs/"),
                    output: ReportingOutputSelect::Upstream,
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
                    url: "postgres://rudder:PASSWORD@127.0.0.1/rudder".to_string(),
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
