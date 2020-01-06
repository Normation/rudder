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

use crate::{configuration::Secret, data::node::NodeId, error::Error};
use serde::{
    de::{Deserializer, Error as SerdeError, Unexpected, Visitor},
    Deserialize,
};
use std::{
    collections::HashSet,
    convert::TryFrom,
    fmt,
    fs::read_to_string,
    net::{IpAddr, Ipv4Addr, SocketAddr},
    path::{Path, PathBuf},
    str::FromStr,
    time::Duration,
};
use toml;
use tracing::debug;

pub type BaseDirectory = PathBuf;
pub type WatchedDirectory = PathBuf;
pub type NodesListFile = PathBuf;
pub type NodesCertsFile = PathBuf;

// For compatibility with int fields containing an integer
fn compat_humantime<'de, D>(d: D) -> Result<Duration, D::Error>
where
    D: Deserializer<'de>,
{
    struct V;

    impl<'de2> Visitor<'de2> for V {
        type Value = Duration;

        fn expecting(&self, fmt: &mut fmt::Formatter) -> fmt::Result {
            fmt.write_str("a duration")
        }

        fn visit_i64<E>(self, v: i64) -> Result<Duration, E>
        where
            E: SerdeError,
        {
            u64::try_from(v)
                .map(|s| Duration::from_secs(s))
                .map_err(|_| E::invalid_value(Unexpected::Signed(v), &self))
        }

        fn visit_str<E>(self, v: &str) -> Result<Duration, E>
        where
            E: SerdeError,
        {
            humantime::parse_duration(v).map_err(|_| E::invalid_value(Unexpected::Str(v), &self))
        }
    }

    d.deserialize_str(V)
}

#[derive(Deserialize, Debug, PartialEq, Eq, Clone)]
// Default can be implemented in serde using the Default trait
pub struct Configuration {
    // general section is mandatory
    pub general: GeneralConfig,
    #[serde(default)]
    pub processing: ProcessingConfig,
    #[serde(default)]
    pub output: OutputConfig,
    #[serde(default)]
    pub remote_run: RemoteRun,
    #[serde(default)]
    pub shared_files: SharedFiles,
    #[serde(default)]
    pub shared_folder: SharedFolder,
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

/// Strategy for default values:
///
/// * `#[serde(default)]` when section is not there
/// * `#[serde(default)]` or `#[serde(default = ...)]` when a value is missing in a section

#[derive(Deserialize, Debug, PartialEq, Eq, Clone)]
pub struct GeneralConfig {
    #[serde(default = "GeneralConfig::default_nodes_list_file")]
    pub nodes_list_file: NodesListFile,
    #[serde(default = "GeneralConfig::default_nodes_certs_file")]
    pub nodes_certs_file: NodesCertsFile,
    /// No possible sane default value
    pub node_id: NodeId,
    #[serde(default = "GeneralConfig::default_listen")]
    pub listen: SocketAddr,
    /// None means using the number of available CPUs
    pub core_threads: Option<usize>,
    #[serde(default = "GeneralConfig::default_blocking_threads")]
    pub blocking_threads: usize,
}

impl GeneralConfig {
    fn default_nodes_list_file() -> PathBuf {
        PathBuf::from("/var/rudder/lib/relay/nodeslist.json")
    }

    fn default_nodes_certs_file() -> PathBuf {
        PathBuf::from("/var/rudder/lib/ssl/allnodescerts.pem")
    }

    fn default_listen() -> SocketAddr {
        SocketAddr::new(IpAddr::V4(Ipv4Addr::new(127, 0, 0, 1)), 3030)
    }

    fn default_blocking_threads() -> usize {
        100
    }
}

#[derive(Deserialize, Debug, PartialEq, Eq, Copy, Clone)]
pub struct CatchupConfig {
    #[serde(deserialize_with = "compat_humantime")]
    #[serde(default = "CatchupConfig::default_catchup_frequency")]
    pub frequency: Duration,
    #[serde(default = "CatchupConfig::default_catchup_limit")]
    pub limit: u64,
}

impl CatchupConfig {
    fn default_catchup_frequency() -> Duration {
        Duration::from_secs(10)
    }

    fn default_catchup_limit() -> u64 {
        50
    }
}

impl Default for CatchupConfig {
    fn default() -> Self {
        Self {
            frequency: Self::default_catchup_frequency(),
            limit: Self::default_catchup_limit(),
        }
    }
}

#[derive(Deserialize, Debug, PartialEq, Eq, Copy, Clone)]
pub struct CleanupConfig {
    #[serde(deserialize_with = "compat_humantime")]
    #[serde(default = "CleanupConfig::default_cleanup_frequency")]
    pub frequency: Duration,
    #[serde(deserialize_with = "compat_humantime")]
    #[serde(default = "CleanupConfig::default_cleanup_retention")]
    pub retention: Duration,
}

impl CleanupConfig {
    /// 1 hour
    fn default_cleanup_frequency() -> Duration {
        Duration::from_secs(3600)
    }

    /// 1 week
    fn default_cleanup_retention() -> Duration {
        Duration::from_secs(3600 * 24 * 7)
    }
}

impl Default for CleanupConfig {
    fn default() -> Self {
        Self {
            frequency: Self::default_cleanup_frequency(),
            retention: Self::default_cleanup_retention(),
        }
    }
}

#[derive(Deserialize, Debug, PartialEq, Eq, Clone, Default)]
pub struct ProcessingConfig {
    #[serde(default)]
    pub inventory: InventoryConfig,
    #[serde(default)]
    pub reporting: ReportingConfig,
}

#[derive(Deserialize, Debug, PartialEq, Eq, Clone)]
pub struct InventoryConfig {
    #[serde(default = "InventoryConfig::default_directory")]
    pub directory: BaseDirectory,
    #[serde(default)]
    pub output: InventoryOutputSelect,
    #[serde(default)]
    pub catchup: CatchupConfig,
    #[serde(default)]
    pub cleanup: CleanupConfig,
}

impl InventoryConfig {
    fn default_directory() -> PathBuf {
        PathBuf::from("/var/rudder/inventories/")
    }
}

impl Default for InventoryConfig {
    fn default() -> Self {
        Self {
            directory: Self::default_directory(),
            output: InventoryOutputSelect::default(),
            catchup: Default::default(),
            cleanup: Default::default(),
        }
    }
}

#[derive(Deserialize, Debug, PartialEq, Eq, Clone)]
#[serde(rename_all = "lowercase")]
pub enum InventoryOutputSelect {
    Upstream,
    Disabled,
}

impl Default for InventoryOutputSelect {
    fn default() -> Self {
        Self::Disabled
    }
}

#[derive(Deserialize, Debug, PartialEq, Eq, Clone)]
pub struct ReportingConfig {
    #[serde(default = "ReportingConfig::default_directory")]
    pub directory: BaseDirectory,
    #[serde(default)]
    pub output: ReportingOutputSelect,
    #[serde(default)]
    pub catchup: CatchupConfig,
    #[serde(default)]
    pub cleanup: CleanupConfig,
    #[serde(default)]
    pub skip_event_types: HashSet<String>,
}

impl ReportingConfig {
    fn default_directory() -> PathBuf {
        PathBuf::from("/var/rudder/reports/")
    }
}

impl Default for ReportingConfig {
    fn default() -> Self {
        Self {
            directory: Self::default_directory(),
            output: ReportingOutputSelect::default(),
            catchup: Default::default(),
            cleanup: Default::default(),
            skip_event_types: Default::default(),
        }
    }
}

#[derive(Deserialize, Debug, PartialEq, Eq, Clone)]
#[serde(rename_all = "lowercase")]
pub enum ReportingOutputSelect {
    Database,
    Upstream,
    Disabled,
}

impl Default for ReportingOutputSelect {
    fn default() -> Self {
        Self::Disabled
    }
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
    #[serde(default = "RemoteRun::default_command")]
    pub command: PathBuf,
    #[serde(default = "RemoteRun::default_use_sudo")]
    pub use_sudo: bool,
}

impl RemoteRun {
    fn default_command() -> PathBuf {
        PathBuf::from("/opt/rudder/bin/rudder")
    }

    fn default_use_sudo() -> bool {
        true
    }
}

impl Default for RemoteRun {
    fn default() -> Self {
        Self {
            command: Self::default_command(),
            use_sudo: Self::default_use_sudo(),
        }
    }
}

#[derive(Deserialize, Debug, PartialEq, Eq, Clone)]
pub struct SharedFiles {
    #[serde(default = "SharedFiles::default_path")]
    pub path: PathBuf,
}

impl SharedFiles {
    fn default_path() -> PathBuf {
        PathBuf::from("/var/rudder/shared-files/")
    }
}

impl Default for SharedFiles {
    fn default() -> Self {
        Self {
            path: Self::default_path(),
        }
    }
}

#[derive(Deserialize, Debug, PartialEq, Eq, Clone)]
pub struct SharedFolder {
    #[serde(default = "SharedFolder::default_path")]
    pub path: PathBuf,
}

impl SharedFolder {
    fn default_path() -> PathBuf {
        PathBuf::from("/var/rudder/configuration-repository/shared-files/")
    }
}

impl Default for SharedFolder {
    fn default() -> Self {
        Self {
            path: Self::default_path(),
        }
    }
}

#[derive(Deserialize, Debug, PartialEq, Eq, Clone, Default)]
pub struct OutputConfig {
    #[serde(default)]
    pub database: DatabaseConfig,
    #[serde(default)]
    pub upstream: UpstreamConfig,
}

#[derive(Deserialize, Debug, PartialEq, Eq, Clone)]
pub struct DatabaseConfig {
    /// URL without the password
    #[serde(default = "DatabaseConfig::default_url")]
    pub url: String,
    /// When the section is there, password is mandatory
    pub password: Secret,
    #[serde(default = "DatabaseConfig::default_max_pool_size")]
    pub max_pool_size: u32,
}

impl DatabaseConfig {
    fn default_url() -> String {
        "postgres://rudder@127.0.0.1/rudder".to_string()
    }

    fn default_max_pool_size() -> u32 {
        10
    }
}

impl Default for DatabaseConfig {
    fn default() -> Self {
        Self {
            url: Self::default_url(),
            password: Default::default(),
            max_pool_size: Self::default_max_pool_size(),
        }
    }
}

#[derive(Deserialize, Debug, PartialEq, Eq, Clone)]
pub struct UpstreamConfig {
    // TODO better URL type
    /// When the section is there, url is mandatory
    pub url: String,
    #[serde(default = "UpstreamConfig::default_user")]
    pub user: String,
    /// When the section is there, password is mandatory
    pub password: Secret,
    #[serde(default = "UpstreamConfig::default_verify_certificates")]
    pub verify_certificates: bool,
    // TODO timeout?
}

impl UpstreamConfig {
    fn default_user() -> String {
        "rudder".to_string()
    }

    fn default_verify_certificates() -> bool {
        true
    }
}

impl Default for UpstreamConfig {
    fn default() -> Self {
        Self {
            url: Default::default(),
            user: Self::default_user(),
            password: Default::default(),
            verify_certificates: Self::default_verify_certificates(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn it_fails_with_empty_config() {
        let empty = "";
        let config = empty.parse::<Configuration>();
        assert!(config.is_err());
    }

    #[test]
    fn it_parses_main_configuration_with_defaults() {
        let default = "[general]\n\
                       node_id = \"root\"";
        let config = default.parse::<Configuration>();
        dbg!(&config);

        let reference = Configuration {
            general: GeneralConfig {
                nodes_list_file: PathBuf::from("/var/rudder/lib/relay/nodeslist.json"),
                nodes_certs_file: PathBuf::from("/var/rudder/lib/ssl/allnodescerts.pem"),
                node_id: "root".to_string(),
                listen: "127.0.0.1:3030".parse().unwrap(),
                core_threads: None,
                blocking_threads: 100,
            },
            processing: ProcessingConfig {
                inventory: InventoryConfig {
                    directory: PathBuf::from("/var/rudder/inventories/"),
                    output: InventoryOutputSelect::Disabled,
                    catchup: CatchupConfig {
                        frequency: Duration::from_secs(10),
                        limit: 50,
                    },
                    cleanup: CleanupConfig {
                        frequency: Duration::from_secs(3600),
                        retention: Duration::from_secs(3600 * 24 * 7),
                    },
                },
                reporting: ReportingConfig {
                    directory: PathBuf::from("/var/rudder/reports/"),
                    output: ReportingOutputSelect::Disabled,
                    catchup: CatchupConfig {
                        frequency: Duration::from_secs(10),
                        limit: 50,
                    },
                    cleanup: CleanupConfig {
                        frequency: Duration::from_secs(3600),
                        retention: Duration::from_secs(3600 * 24 * 7),
                    },
                    skip_event_types: HashSet::new(),
                },
            },
            output: OutputConfig {
                upstream: UpstreamConfig {
                    url: "".to_string(),
                    user: "rudder".to_string(),
                    password: Secret::new("".to_string()),
                    verify_certificates: true,
                },
                database: DatabaseConfig {
                    url: "postgres://rudder@127.0.0.1/rudder".to_string(),
                    password: Secret::new("".to_string()),
                    max_pool_size: 10,
                },
            },
            remote_run: RemoteRun {
                command: PathBuf::from("/opt/rudder/bin/rudder"),
                use_sudo: true,
            },
            shared_files: SharedFiles {
                path: PathBuf::from("/var/rudder/shared-files/"),
            },
            shared_folder: SharedFolder {
                path: PathBuf::from("/var/rudder/configuration-repository/shared-files/"),
            },
        };

        assert_eq!(config.unwrap(), reference);
    }

    #[test]
    fn it_fails_when_password_is_missing() {
        let default = "[general]\n\
                       node_id = \"root\"\n\
                       [output.database]\n";
        let with_password = format!("{}\npassword = \"test\"", default);
        assert!(default.parse::<Configuration>().is_err());
        assert!(with_password.parse::<Configuration>().is_ok());
    }

    #[test]
    fn it_works_with_unknown_entries() {
        let default = "[general]\n\
                       node_id = \"root\"\n\
                       unknown = \"entry\"";
        assert!(default.parse::<Configuration>().is_ok());
    }

    #[test]
    fn it_parses_main_configuration() {
        let config = Configuration::new("tests/files/config/");

        let reference = Configuration {
            general: GeneralConfig {
                nodes_list_file: PathBuf::from("tests/files/nodeslist.json"),
                nodes_certs_file: PathBuf::from("tests/files/keys/nodescerts.pem"),
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
                        frequency: Duration::from_secs(10),
                        limit: 50,
                    },
                    cleanup: CleanupConfig {
                        frequency: Duration::from_secs(10),
                        retention: Duration::from_secs(10),
                    },
                },
                reporting: ReportingConfig {
                    directory: PathBuf::from("target/tmp/reporting/"),
                    output: ReportingOutputSelect::Database,
                    catchup: CatchupConfig {
                        frequency: Duration::from_secs(10),
                        limit: 50,
                    },
                    cleanup: CleanupConfig {
                        frequency: Duration::from_secs(30),
                        retention: Duration::from_secs(30 * 60 + 20),
                    },
                    skip_event_types: HashSet::new(),
                },
            },
            output: OutputConfig {
                upstream: UpstreamConfig {
                    url: "https://127.0.0.1:8080".to_string(),
                    user: "rudder".to_string(),
                    password: Secret::new("password".to_string()),
                    verify_certificates: false,
                },
                database: DatabaseConfig {
                    url: "postgres://rudderreports@127.0.0.1/rudder".to_string(),
                    password: Secret::new("PASSWORD".to_string()),
                    max_pool_size: 5,
                },
            },
            remote_run: RemoteRun {
                command: PathBuf::from("tests/api_remote_run/fake_agent.sh"),
                use_sudo: false,
            },
            shared_files: SharedFiles {
                path: PathBuf::from("tests/api_shared_files"),
            },
            shared_folder: SharedFolder {
                path: PathBuf::from("tests/api_shared_folder"),
            },
        };
        assert_eq!(config.unwrap(), reference);
    }
}
