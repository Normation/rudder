// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use std::{
    collections::HashSet,
    convert::TryFrom,
    fmt,
    fs::read_to_string,
    path::{Path, PathBuf},
    str::FromStr,
    time::Duration,
};

use anyhow::{anyhow, bail, Context, Error};
use secrecy::SecretString;
use serde::{
    de::{Deserializer, Error as SerdeError, Unexpected, Visitor},
    Deserialize,
};
use serde_inline_default::serde_inline_default;
use tracing::{debug, warn};

use crate::data::node::NodeId;

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

    impl Visitor<'_> for V {
        type Value = Duration;

        fn expecting(&self, fmt: &mut fmt::Formatter) -> fmt::Result {
            fmt.write_str("a duration")
        }

        fn visit_i64<E>(self, v: i64) -> Result<Duration, E>
        where
            E: SerdeError,
        {
            u64::try_from(v)
                .map(Duration::from_secs)
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
    #[serde(default)]
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
    #[serde(default)]
    pub rsync: RsyncConfig,
}

impl Configuration {
    pub fn new<P: AsRef<Path>>(path: P) -> Result<Self, Error> {
        // parse (checks types)
        let res = read_to_string(path.as_ref().join("main.conf"))
            .with_context(|| {
                format!(
                    "Could not read main configuration file from {}",
                    path.as_ref().join("main.conf").display()
                )
            })?
            .parse::<Self>();
        // check logic
        let res = res.and_then(|c| c.validate());
        if let Ok(ref cfg) = res {
            debug!("Parsed main configuration:\n{:#?}", &cfg);
            for e in cfg.warnings() {
                // log warnings
                warn!("{}", e);
            }
        }
        res
    }

    /// Used to validate what is not validated by typing
    fn validate(self) -> Result<Self, Error> {
        // If not on root we need an upstream server
        if &self.node_id()? != "root" && self.output.upstream.host.is_empty() {
            bail!("missing upstream server configuration");
        }

        // When upstream is selected, it must have a password
        if (matches!(
            self.processing.reporting.output,
            ReportingOutputSelect::Upstream
        ) || matches!(
            self.processing.inventory.output,
            InventoryOutputSelect::Upstream
        )) && self.output.upstream.password.is_none()
        {
            bail!("missing upstream password configuration");
        }

        // When database is selected, it must have a password
        if matches!(
            self.processing.reporting.output,
            ReportingOutputSelect::Database
        ) && self.output.database.password.is_none()
        {
            bail!("missing database password configuration");
        }

        Ok(self)
    }

    /// Check for valid configs known to present security or stability risks.
    pub fn warnings(&self) -> Vec<Error> {
        let mut warnings = vec![];

        if !self.general.certificate_validation && !self.general.public_key_pinning {
            warnings.push(anyhow!(
                "No security mechanism are enabled for peer authentication. This is a security risk, please enable at least one of certificate validation or public key pinning."
            ));
        }

        warnings
    }

    /// Read current node_id, and handle override by node_id
    /// Can be removed once node_id is removed.
    pub fn node_id(&self) -> Result<NodeId, Error> {
        Ok(match &self.general.node_id {
            Some(id) => {
                debug!("using {} node_id form configuration file", id);
                id.clone()
            }
            None => {
                debug!(
                    "reading node_id from {}",
                    &self.general.node_id_file.display()
                );
                read_to_string(&self.general.node_id_file)?
            }
        })
    }

    /// Gives current url of the upstream relay API
    pub fn upstream_url(&self) -> String {
        format!(
            // TODO compute once
            "https://{}:{}/",
            self.output.upstream.host, self.general.https_port
        )
    }

    pub fn upstream_pkey_hash(&self) -> &str {
        self.output
            .upstream
            .server_public_key_hash
            .as_ref()
            .map(|s| s.as_str())
            .unwrap_or("")
    }
}

impl FromStr for Configuration {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        Ok(toml::from_str(s)?)
    }
}

impl Default for Configuration {
    fn default() -> Self {
        toml::from_str::<Self>("").unwrap()
    }
}

/// Strategy for default values:
///
/// * `#[serde(default)]` when section is not there
/// * `#[serde(default)]` or `#[serde(default = ...)]` when a value is missing in a section

#[serde_inline_default]
#[derive(Deserialize, Debug, PartialEq, Eq, Clone)]
pub struct GeneralConfig {
    #[serde_inline_default(PathBuf::from("/var/rudder/lib/relay/nodeslist.json"))]
    pub nodes_list_file: NodesListFile,
    #[serde_inline_default(PathBuf::from("/var/rudder/lib/ssl/allnodescerts.pem"))]
    // needs to be /var/rudder/lib/ssl/nodescerts.pem on simple relays
    pub nodes_certs_file: NodesCertsFile,
    /// Has priority over node_id_file, use the
    /// `Configuration::node_id()` method to get correct value
    node_id: Option<NodeId>,
    /// File containing the node id
    #[serde_inline_default(PathBuf::from("/opt/rudder/etc/uuid.hive"))]
    node_id_file: PathBuf,
    #[serde_inline_default("127.0.0.1:3030".to_string())]
    pub listen: String,
    /// None means using the number of available CPUs
    pub core_threads: Option<usize>,
    /// Max number of threads for the blocking operations
    pub blocking_threads: Option<usize>,
    /// Port to use for communication
    #[serde_inline_default(443)]
    pub https_port: u16,
    /// Timeout for idle connections being kept-alive
    #[serde(deserialize_with = "compat_humantime")]
    #[serde_inline_default(Duration::from_secs(2))]
    pub https_idle_timeout: Duration,
    #[serde_inline_default(true)]
    pub certificate_validation: bool,
    #[serde_inline_default(false)]
    pub public_key_pinning: bool,
    pub ca_path: Option<PathBuf>,
}

impl Default for GeneralConfig {
    fn default() -> Self {
        toml::from_str::<Self>("").unwrap()
    }
}

#[serde_inline_default]
#[derive(Deserialize, Debug, PartialEq, Eq, Copy, Clone)]
pub struct CatchupConfig {
    #[serde(deserialize_with = "compat_humantime")]
    #[serde_inline_default(Duration::from_secs(10))]
    pub frequency: Duration,
    #[serde_inline_default(50)]
    pub limit: u64,
}

impl Default for CatchupConfig {
    fn default() -> Self {
        toml::from_str::<Self>("").unwrap()
    }
}

#[serde_inline_default]
#[derive(Deserialize, Debug, PartialEq, Eq, Copy, Clone)]
pub struct CleanupConfig {
    #[serde(deserialize_with = "compat_humantime")]
    #[serde_inline_default(Duration::from_secs(3600))]
    pub frequency: Duration,
    #[serde(deserialize_with = "compat_humantime")]
    #[serde_inline_default(Duration::from_secs(3600 * 24 * 7))]
    pub retention: Duration,
}

impl Default for CleanupConfig {
    fn default() -> Self {
        toml::from_str::<Self>("").unwrap()
    }
}

#[derive(Deserialize, Debug, PartialEq, Eq, Clone, Default)]
pub struct ProcessingConfig {
    #[serde(default)]
    pub inventory: InventoryConfig,
    #[serde(default)]
    pub reporting: ReportingConfig,
}

#[serde_inline_default]
#[derive(Deserialize, Debug, PartialEq, Eq, Clone)]
pub struct InventoryConfig {
    #[serde_inline_default(PathBuf::from("/var/rudder/inventories/"))]
    pub directory: BaseDirectory,
    #[serde(default)]
    pub output: InventoryOutputSelect,
    #[serde(default)]
    pub catchup: CatchupConfig,
    #[serde(default)]
    pub cleanup: CleanupConfig,
}

impl Default for InventoryConfig {
    fn default() -> Self {
        toml::from_str::<Self>("").unwrap()
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

#[serde_inline_default]
#[derive(Deserialize, Debug, PartialEq, Eq, Clone)]
pub struct ReportingConfig {
    #[serde_inline_default(PathBuf::from("/var/rudder/reports/"))]
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

impl Default for ReportingConfig {
    fn default() -> Self {
        toml::from_str::<Self>("").unwrap()
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

#[serde_inline_default]
#[derive(Deserialize, Debug, PartialEq, Eq, Clone)]
pub struct RemoteRun {
    /// Enable remote-run feature
    #[serde_inline_default(true)]
    pub enabled: bool,
    #[serde_inline_default(PathBuf::from("/opt/rudder/bin/rudder"))]
    pub command: PathBuf,
    #[serde_inline_default(true)]
    pub use_sudo: bool,
}

impl Default for RemoteRun {
    fn default() -> Self {
        toml::from_str::<Self>("").unwrap()
    }
}

#[serde_inline_default]
#[derive(Deserialize, Debug, PartialEq, Eq, Copy, Clone)]
pub struct SharedFilesCleanupConfig {
    #[serde(deserialize_with = "compat_humantime")]
    #[serde_inline_default(Duration::from_secs(600))]
    pub frequency: Duration,
}

impl Default for SharedFilesCleanupConfig {
    fn default() -> Self {
        toml::from_str::<Self>("").unwrap()
    }
}

#[serde_inline_default]
#[derive(Deserialize, Debug, PartialEq, Eq, Clone)]
pub struct SharedFiles {
    #[serde_inline_default(PathBuf::from("/var/rudder/shared-files/"))]
    pub path: PathBuf,
    #[serde(default)]
    pub cleanup: SharedFilesCleanupConfig,
}

impl Default for SharedFiles {
    fn default() -> Self {
        toml::from_str::<Self>("").unwrap()
    }
}

#[serde_inline_default]
#[derive(Deserialize, Debug, PartialEq, Eq, Clone)]
pub struct SharedFolder {
    #[serde_inline_default(PathBuf::from("/var/rudder/configuration-repository/shared-files/"))]
    pub path: PathBuf,
}

impl Default for SharedFolder {
    fn default() -> Self {
        toml::from_str::<Self>("").unwrap()
    }
}

#[derive(Deserialize, Debug, PartialEq, Eq, Clone, Default)]
pub struct OutputConfig {
    #[serde(default)]
    pub database: DatabaseConfig,
    #[serde(default)]
    pub upstream: UpstreamConfig,
}

#[serde_inline_default]
#[derive(Deserialize, Debug, Clone)]
pub struct DatabaseConfig {
    /// URL without the password
    #[serde_inline_default("postgres://rudder@127.0.0.1/rudder".to_string())]
    pub url: String,
    #[serde(default)]
    pub password: Option<SecretString>,
    #[serde_inline_default(10)]
    pub max_pool_size: u32,
}

impl PartialEq for DatabaseConfig {
    fn eq(&self, other: &Self) -> bool {
        self.url == other.url && self.max_pool_size == other.max_pool_size
    }
}

impl Eq for DatabaseConfig {}

impl Default for DatabaseConfig {
    fn default() -> Self {
        toml::from_str::<Self>("").unwrap()
    }
}

#[serde_inline_default]
#[derive(Deserialize, Debug, Clone)]
pub struct UpstreamConfig {
    /// When the section is there, host is mandatory
    #[serde(default)]
    host: String,
    #[serde_inline_default("rudder".to_string())]
    pub user: String,
    #[serde(default)]
    pub password: Option<SecretString>,
    /// Default password, to be used for new inventories
    #[serde_inline_default(SecretString::new("rudder".into()))]
    pub default_password: SecretString,
    /// Allows specifying the upstream server certificate's public key hash
    /// Only used (and required) when pinning is enabled.
    pub server_public_key_hash: Option<String>,
    // TODO timeout?
}

impl PartialEq for UpstreamConfig {
    fn eq(&self, other: &Self) -> bool {
        self.host == other.host
            && self.user == other.user
            && self.server_public_key_hash == other.server_public_key_hash
    }
}

impl Eq for UpstreamConfig {}

impl Default for UpstreamConfig {
    fn default() -> Self {
        toml::from_str::<Self>("").unwrap()
    }
}

#[serde_inline_default]
#[derive(Deserialize, Debug, PartialEq, Eq, Clone)]
pub struct RsyncConfig {
    // where to listen on
    #[serde_inline_default("localhost:5310".into())]
    listen: String,

    // False to allow non authenticated clients
    #[serde_inline_default(true)]
    authentication: bool,
}

impl Default for RsyncConfig {
    fn default() -> Self {
        toml::from_str::<Self>("").unwrap()
    }
}

#[cfg(test)]
mod tests {
    use pretty_assertions::assert_eq;

    use super::*;

    #[test]
    fn it_parses_listen_with_hostname() {
        let default = "[general]\n\
                       node_id = \"root\"\n\
                       listen = \"relayd:3030\"";
        let config = default.parse::<Configuration>().unwrap();
        assert_eq!(config.general.listen, "relayd:3030");
    }

    #[test]
    fn it_parses_hardcoded_node_id() {
        let default = "[general]\n\
        node_id = \"test\"\n";
        let config = default.parse::<Configuration>().unwrap();
        assert_eq!(config.node_id().unwrap(), "test".to_string());
    }

    #[test]
    fn it_parses_upstream_url() {
        let default = "[general]\n\
        node_id = \"test\"\n\
        [output.upstream]\n\
        url = \"https://myserver/\"\n\
        password = \"my_password\"";
        let config = default.parse::<Configuration>().unwrap();
        assert_eq!(config.upstream_url(), "https://myserver/".to_string());
        assert!(config.validate().is_ok());
    }

    #[test]
    fn it_parses_main_configuration_with_defaults() {
        let config = "".parse::<Configuration>().unwrap();

        let reference = Configuration {
            general: GeneralConfig {
                nodes_list_file: PathBuf::from("/var/rudder/lib/relay/nodeslist.json"),
                nodes_certs_file: PathBuf::from("/var/rudder/lib/ssl/allnodescerts.pem"),
                node_id: None,
                node_id_file: PathBuf::from("/opt/rudder/etc/uuid.hive"),
                listen: "127.0.0.1:3030".parse().unwrap(),
                core_threads: None,
                blocking_threads: None,
                https_port: 443,
                https_idle_timeout: Duration::from_secs(2),
                certificate_validation: true,
                public_key_pinning: true,
                ca_path: Some(PathBuf::from("tests/files/keys/ca.pem")),
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
                    host: "".to_string(),
                    user: "rudder".to_string(),
                    password: None,
                    default_password: "".into(),
                    server_public_key_hash: None,
                },
                database: DatabaseConfig {
                    url: "postgres://rudder@127.0.0.1/rudder".to_string(),
                    password: None,
                    max_pool_size: 10,
                },
            },
            remote_run: RemoteRun {
                command: PathBuf::from("/opt/rudder/bin/rudder"),
                use_sudo: true,
                enabled: true,
            },
            shared_files: SharedFiles {
                path: PathBuf::from("/var/rudder/shared-files/"),
                cleanup: SharedFilesCleanupConfig {
                    frequency: Duration::from_secs(600),
                },
            },
            shared_folder: SharedFolder {
                path: PathBuf::from("/var/rudder/configuration-repository/shared-files/"),
            },
            rsync: RsyncConfig {
                listen: "localhost:5310".to_string(),
                authentication: true,
            },
        };

        assert_eq!(config, reference);
        // with default options we are missing required fields
        assert!(config.validate().is_err());
    }

    #[test]
    fn it_fails_when_password_is_missing() {
        let missing_password = "[general]\n\
                       node_id = \"root\"\n\
                       [processing.reporting]\n\
                       directory = \"target/tmp/reporting/\"\n\
                       output = \"database\"\n\
                       [output.database]\n";
        let conf = missing_password.parse::<Configuration>();
        assert!(conf.is_ok());
        assert!(conf.unwrap().validate().is_err());
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
        let config = Configuration::new("tests/files/config/").unwrap();

        let reference = Configuration {
            general: GeneralConfig {
                nodes_list_file: PathBuf::from("tests/files/nodeslist.json"),
                nodes_certs_file: PathBuf::from("tests/files/keys/nodescerts.pem"),
                node_id: None,
                node_id_file: PathBuf::from("tests/files/config/uuid.hive"),
                listen: "127.0.0.1:3030".parse().unwrap(),
                core_threads: None,
                blocking_threads: Some(512),
                https_port: 4443,
                https_idle_timeout: Duration::from_secs(42),
                certificate_validation: true,
                public_key_pinning: true,
                ca_path: Some(PathBuf::from("tests/files/keys/ca.pem")),
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
                    host: "rudder.example.com".to_string(),
                    user: "rudder".to_string(),
                    password: Some("password".into()),
                    default_password: "rudder".into(),
                    server_public_key_hash: Some(
                        "sha256//4BmcSBb6WJebDT5p0Y6yKHvmlyh193YN5no9Pj6D5Vo=".into(),
                    ),
                },
                database: DatabaseConfig {
                    url: "postgres://rudderreports@postgres/rudder".to_string(),
                    password: Some("PASSWORD".into()),
                    max_pool_size: 5,
                },
            },
            remote_run: RemoteRun {
                command: PathBuf::from("tests/api_remote_run/fake_agent.sh"),
                use_sudo: false,
                enabled: true,
            },
            shared_files: SharedFiles {
                path: PathBuf::from("tests/api_shared_files"),
                cleanup: SharedFilesCleanupConfig {
                    frequency: Duration::from_secs(43),
                },
            },
            shared_folder: SharedFolder {
                path: PathBuf::from("tests/api_shared_folder"),
            },
            rsync: RsyncConfig {
                listen: "localhost:1234".to_string(),
                authentication: false,
            },
        };
        assert_eq!(config, reference);
        assert_eq!(config.node_id().unwrap(), "root".to_string());
        assert_eq!(
            config.upstream_url(),
            "https://rudder.example.com:4443/".to_string()
        );
        assert!(config.validate().is_ok());
    }
}
