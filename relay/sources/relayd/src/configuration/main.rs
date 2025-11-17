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
use crate::http_client::PemCertificate;

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

    pub fn peer_authentication(&self) -> Result<PeerAuthentication, Error> {
        Ok(match self.general.peer_authentication {
            PeerAuthenticationType::CertPinning => PeerAuthentication::CertPinning,
            PeerAuthenticationType::CertValidation => {
                if let Some(p) = self.general.ca_path.as_ref() {
                    let cert = read_to_string(p)
                        .with_context(|| {
                            format!("Could not read CA certificate from {}", p.display())
                        })
                        .unwrap_or_else(|e| {
                            warn!("{}", e);
                            "".to_string()
                        })
                        .into();
                    PeerAuthentication::CertValidation(Some(cert))
                } else {
                    PeerAuthentication::CertValidation(None)
                }
            }
            PeerAuthenticationType::DangerousNone => PeerAuthentication::DangerousNone,
        })
    }

    /// Check for valid configs known to present security or stability risks
    pub fn warnings(&self) -> Vec<Error> {
        let mut warnings = vec![];

        if self.peer_authentication().unwrap() == PeerAuthentication::DangerousNone {
            warnings.push(anyhow!("Certificate verification is disabled"));
        }

        warnings
    }

    pub fn upstream_url(&self) -> String {
        format!(
            // TODO compute once
            "https://{}:{}/",
            self.output.upstream.host, self.general.https_port
        )
    }

    /// Read current node_id, and handle override by node_id
    /// Can be removed once node_id is removed
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
    /// Which certificate validation model to use
    #[serde_inline_default(PeerAuthenticationType::CertPinning)]
    peer_authentication: PeerAuthenticationType,
    ca_path: Option<PathBuf>,
}

impl Default for GeneralConfig {
    fn default() -> Self {
        toml::from_str::<Self>("").unwrap()
    }
}

#[derive(Deserialize, Debug, PartialEq, Eq, Clone, Copy)]
#[serde(rename_all = "snake_case")]
pub enum PeerAuthenticationType {
    CertPinning,
    CertValidation,
    DangerousNone,
}

#[derive(Deserialize, Debug, PartialEq, Eq, Clone)]
#[serde(rename_all = "snake_case")]
pub enum PeerAuthentication {
    CertPinning,
    CertValidation(Option<PemCertificate>),
    DangerousNone,
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

#[derive(Default, Deserialize, Debug, PartialEq, Eq, Clone)]
#[serde(rename_all = "lowercase")]
pub enum InventoryOutputSelect {
    Upstream,
    #[default]
    Disabled,
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

#[derive(Default, Deserialize, Debug, PartialEq, Eq, Clone)]
#[serde(rename_all = "lowercase")]
pub enum ReportingOutputSelect {
    Database,
    Upstream,
    #[default]
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
    /// Allows specifying the root certificate path
    /// Used for our Rudder PKI
    /// Not used if the verification model is not `Rudder`.
    #[serde_inline_default(PathBuf::from("/var/rudder/lib/ssl/policy_server.pem"))]
    pub server_certificate_file: PathBuf,
    // TODO timeout?
}

impl PartialEq for UpstreamConfig {
    fn eq(&self, other: &Self) -> bool {
        self.host == other.host
            && self.user == other.user
            && self.server_certificate_file == other.server_certificate_file
    }
}

impl Eq for UpstreamConfig {}

impl Default for UpstreamConfig {
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
                peer_authentication: PeerAuthenticationType::CertPinning,
                ca_path: None,
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
                    server_certificate_file: PathBuf::from("/var/rudder/lib/ssl/policy_server.pem"),
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
                peer_authentication: PeerAuthenticationType::CertPinning,
                ca_path: None,
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
                    server_certificate_file: PathBuf::from(
                        "tests/files/keys/e745a140-40bc-4b86-b6dc-084488fc906b.cert",
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
        };
        assert_eq!(config, reference);
        assert_eq!(config.node_id().unwrap(), "root".to_string());
        assert!(config.validate().is_ok());
    }
}
