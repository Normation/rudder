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

use anyhow::{anyhow, Context, Error};
use secrecy::SecretString;
use serde::{
    de::{Deserializer, Error as SerdeError, Unexpected, Visitor},
    Deserialize,
};
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
        if &self.node_id()? != "root"
            && self.output.upstream.host.is_empty()
            && (self
                .output
                .upstream
                .url
                .as_ref()
                .map(|u| u.is_empty())
                .unwrap_or(true))
        {
            return Err(anyhow!("missing upstream server configuration"));
        }

        Ok(self)
    }

    /// Check for valid configs known to present security or stability risks
    pub fn warnings(&self) -> Vec<Error> {
        let mut warnings = vec![];

        if self.peer_authentication() == PeerAuthentication::DangerousNone {
            warnings.push(anyhow!("Certificate verification is disabled"));
        }

        warnings
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

    pub fn peer_authentication(&self) -> PeerAuthentication {
        // compute actual model
        if !self.output.upstream.verify_certificates {
            warn!("output.upstream.verify_certificates parameter is deprecated, use general.peer_authentication instead");
            PeerAuthentication::DangerousNone
        } else {
            self.general.peer_authentication
        }
    }

    /// Gives current url of the upstream relay API
    /// Can be removed once upstream.url is removed
    pub fn upstream_url(&self) -> String {
        match &self.output.upstream.url {
            Some(id) => {
                warn!("upstream.url setting is deprecated, use upstream.host and general.https_port instead");
                id.clone()
            }
            None => format!(
                // TODO compute once
                "https://{}:{}/",
                self.output.upstream.host, self.general.https_port
            ),
        }
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
    // needs to be /var/rudder/lib/ssl/nodescerts.pem on simple relays
    pub nodes_certs_file: NodesCertsFile,
    /// Has priority over node_id_file, use the
    /// `Configuration::node_id()` method to get correct value
    node_id: Option<NodeId>,
    /// File containing the node id
    #[serde(default = "GeneralConfig::default_node_id_file")]
    node_id_file: PathBuf,
    #[serde(default = "GeneralConfig::default_listen")]
    pub listen: String,
    /// None means using the number of available CPUs
    pub core_threads: Option<usize>,
    /// Max number of threads for the blocking operations
    pub blocking_threads: Option<usize>,
    /// Port to use for communication
    #[serde(default = "GeneralConfig::default_https_port")]
    pub https_port: u16,
    /// Timeout for idle connections being kept-alive
    #[serde(deserialize_with = "compat_humantime")]
    #[serde(default = "GeneralConfig::default_https_idle_timeout")]
    pub https_idle_timeout: Duration,
    /// Which certificate validation model to use
    #[serde(default = "GeneralConfig::default_peer_authentication")]
    peer_authentication: PeerAuthentication,
}

impl GeneralConfig {
    fn default_nodes_list_file() -> PathBuf {
        PathBuf::from("/var/rudder/lib/relay/nodeslist.json")
    }

    fn default_nodes_certs_file() -> PathBuf {
        // config for root servers
        PathBuf::from("/var/rudder/lib/ssl/allnodescerts.pem")
    }

    fn default_node_id_file() -> PathBuf {
        PathBuf::from("/opt/rudder/etc/uuid.hive")
    }

    fn default_listen() -> String {
        "127.0.0.1:3030".to_string()
    }

    fn default_https_port() -> u16 {
        443
    }

    fn default_https_idle_timeout() -> Duration {
        Duration::from_secs(2)
    }

    fn default_peer_authentication() -> PeerAuthentication {
        // For compatibility
        PeerAuthentication::SystemRootCerts
    }
}

impl Default for GeneralConfig {
    fn default() -> Self {
        Self {
            nodes_list_file: Self::default_nodes_list_file(),
            nodes_certs_file: Self::default_nodes_certs_file(),
            node_id_file: Self::default_node_id_file(),
            node_id: None,
            listen: Self::default_listen(),
            core_threads: None,
            blocking_threads: None,
            https_port: Self::default_https_port(),
            https_idle_timeout: Self::default_https_idle_timeout(),
            peer_authentication: Self::default_peer_authentication(),
        }
    }
}

#[derive(Deserialize, Debug, PartialEq, Eq, Clone, Copy)]
#[serde(rename_all = "snake_case")]
pub enum PeerAuthentication {
    CertPinning,
    SystemRootCerts,
    DangerousNone,
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
    /// Enable remote-run feature
    #[serde(default = "RemoteRun::default_enabled")]
    pub enabled: bool,
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

    fn default_enabled() -> bool {
        true
    }
}

impl Default for RemoteRun {
    fn default() -> Self {
        Self {
            enabled: Self::default_enabled(),
            command: Self::default_command(),
            use_sudo: Self::default_use_sudo(),
        }
    }
}

#[derive(Deserialize, Debug, PartialEq, Eq, Copy, Clone)]
pub struct SharedFilesCleanupConfig {
    #[serde(deserialize_with = "compat_humantime")]
    #[serde(default = "CleanupConfig::default_cleanup_frequency")]
    pub frequency: Duration,
}

impl SharedFilesCleanupConfig {
    /// 10 minutes
    fn default_cleanup_frequency() -> Duration {
        Duration::from_secs(600)
    }
}

impl Default for SharedFilesCleanupConfig {
    fn default() -> Self {
        Self {
            frequency: Self::default_cleanup_frequency(),
        }
    }
}

#[derive(Deserialize, Debug, PartialEq, Eq, Clone)]
pub struct SharedFiles {
    #[serde(default = "SharedFiles::default_path")]
    pub path: PathBuf,
    #[serde(default)]
    pub cleanup: SharedFilesCleanupConfig,
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
            cleanup: SharedFilesCleanupConfig::default(),
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

#[derive(Deserialize, Debug, Clone)]
pub struct DatabaseConfig {
    /// URL without the password
    #[serde(default = "DatabaseConfig::default_url")]
    pub url: String,
    /// When the section is there, password is mandatory
    pub password: SecretString,
    #[serde(default = "DatabaseConfig::default_max_pool_size")]
    pub max_pool_size: u32,
}

impl PartialEq for DatabaseConfig {
    fn eq(&self, other: &Self) -> bool {
        self.url == other.url && self.max_pool_size == other.max_pool_size
    }
}

impl Eq for DatabaseConfig {}

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
            password: "".into(),
            max_pool_size: Self::default_max_pool_size(),
        }
    }
}

#[derive(Deserialize, Debug, Clone)]
pub struct UpstreamConfig {
    /// DEPRECATED: use host and global.https_port
    #[serde(default)]
    url: Option<String>,
    /// When the section is there, host is mandatory
    #[serde(default)]
    host: String,
    #[serde(default = "UpstreamConfig::default_user")]
    pub user: String,
    /// When the section is there, password is mandatory
    pub password: SecretString,
    /// Default password, to be used for new inventories
    #[serde(default = "UpstreamConfig::default_default_password")]
    pub default_password: SecretString,
    #[serde(default = "UpstreamConfig::default_verify_certificates")]
    /// Allows to completely disable certificate validation.
    ///
    /// If true, https is required for all connections
    ///
    /// This preserves compatibility with 6.X configs
    /// DEPRECATED: replaced by certificate_verification_mode = DangerousNone
    pub verify_certificates: bool,
    /// Allows specifying the root certificate path
    /// Used for our Rudder PKI
    /// Not used if the verification model is not `Rudder`.
    #[serde(default = "UpstreamConfig::default_server_certificate_file")]
    pub server_certificate_file: PathBuf,
    // TODO timeout?
}

impl PartialEq for UpstreamConfig {
    fn eq(&self, other: &Self) -> bool {
        self.url == other.url
            && self.host == other.host
            && self.user == other.user
            && self.verify_certificates == other.verify_certificates
            && self.server_certificate_file == other.server_certificate_file
    }
}

impl Eq for UpstreamConfig {}

impl UpstreamConfig {
    fn default_user() -> String {
        "rudder".to_string()
    }

    fn default_verify_certificates() -> bool {
        true
    }

    fn default_default_password() -> SecretString {
        "rudder".into()
    }

    fn default_server_certificate_file() -> PathBuf {
        PathBuf::from("/var/rudder/lib/ssl/policy_server.pem")
    }
}

impl Default for UpstreamConfig {
    fn default() -> Self {
        Self {
            url: Default::default(),
            host: Default::default(),
            user: Self::default_user(),
            password: "".into(),
            default_password: "".into(),
            verify_certificates: Self::default_verify_certificates(),
            server_certificate_file: Self::default_server_certificate_file(),
        }
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
                peer_authentication: PeerAuthentication::SystemRootCerts,
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
                    url: None,
                    host: "".to_string(),
                    user: "rudder".to_string(),
                    password: "".into(),
                    default_password: "".into(),
                    verify_certificates: true,
                    server_certificate_file: PathBuf::from("/var/rudder/lib/ssl/policy_server.pem"),
                },
                database: DatabaseConfig {
                    url: "postgres://rudder@127.0.0.1/rudder".to_string(),
                    password: "".into(),
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
                peer_authentication: PeerAuthentication::CertPinning,
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
                    url: None,
                    host: "rudder.example.com".to_string(),
                    user: "rudder".to_string(),
                    password: "password".into(),
                    default_password: "rudder".into(),
                    verify_certificates: true,
                    server_certificate_file: PathBuf::from(
                        "tests/files/keys/e745a140-40bc-4b86-b6dc-084488fc906b.cert",
                    ),
                },
                database: DatabaseConfig {
                    url: "postgres://rudderreports@postgres/rudder".to_string(),
                    password: "PASSWORD".into(),
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
        assert_eq!(
            config.upstream_url(),
            "https://rudder.example.com:4443/".to_string()
        );
        assert!(config.validate().is_ok());
    }
}
