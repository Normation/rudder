//! Command line and configuration file (TOML).
//!
//! Every setting has a default, so an empty file is valid. Unknown keys are rejected to catch
//! typos.

use std::{
    net::SocketAddr,
    path::{Path, PathBuf},
};

use anyhow::{Context, Result};
use clap::Parser;
use serde::Deserialize;

const DEFAULT_CONFIG: &str = "/opt/rudder/etc/rudder-mcp.conf";

#[derive(Debug, Parser)]
#[command(version, about = "Rudder MCP server")]
pub struct Cli {
    /// Configuration file
    #[arg(short, long, default_value = DEFAULT_CONFIG)]
    pub config: PathBuf,
}

#[derive(Debug, Default, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Config {
    pub server: ServerConfig,
    pub api: ApiConfig,
    pub rudderc: RuddercConfig,
}

impl Config {
    pub fn load(path: &Path) -> Result<Self> {
        let content = std::fs::read_to_string(path)
            .with_context(|| format!("could not read configuration file {}", path.display()))?;
        toml::from_str(&content)
            .with_context(|| format!("invalid configuration file {}", path.display()))
    }
}

#[derive(Debug, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct ServerConfig {
    /// Address the MCP endpoint listens on
    pub listen: SocketAddr,
    /// Only offer read-only tools (default). Callers still need the rights on their Rudder API
    /// token to use write tools.
    pub read_only: bool,
}

impl Default for ServerConfig {
    fn default() -> Self {
        Self {
            listen: SocketAddr::from(([127, 0, 0, 1], 8000)),
            read_only: true,
        }
    }
}

#[derive(Debug, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct ApiConfig {
    /// Rudder API base URL
    pub url: ApiUrl,
    /// Disable TLS certificate verification, e.g. for the self-signed certificate of a default
    /// install. Forwarded tokens are then exposed to anyone able to intercept the connection.
    pub tls_skip_verify: bool,
}

impl Default for ApiConfig {
    fn default() -> Self {
        Self {
            url: ApiUrl::try_from("https://localhost/rudder/api/latest".to_owned())
                .expect("default API URL is valid"),
            tls_skip_verify: false,
        }
    }
}

/// Rudder API base URL: https only, as tokens are sent to it. Without trailing `/`.
#[derive(Debug, Clone, Deserialize)]
#[serde(try_from = "String")]
pub struct ApiUrl(String);

impl TryFrom<String> for ApiUrl {
    type Error = String;

    fn try_from(url: String) -> Result<Self, Self::Error> {
        let parsed = reqwest::Url::parse(&url).map_err(|e| format!("invalid URL '{url}': {e}"))?;
        if parsed.scheme() != "https" {
            return Err(format!("must be an https URL, got '{url}'"));
        }
        Ok(Self(parsed.as_str().trim_end_matches('/').to_owned()))
    }
}

impl ApiUrl {
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

#[derive(Debug, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct RuddercConfig {
    /// `rudderc` binary
    pub path: PathBuf,
}

impl Default for RuddercConfig {
    fn default() -> Self {
        Self {
            path: PathBuf::from("/opt/rudder/bin/rudderc"),
        }
    }
}

#[cfg(test)]
impl ApiUrl {
    /// Any URL, for tests against a plain HTTP mock of the Rudder API
    pub fn for_tests(url: &str) -> Self {
        Self(url.trim_end_matches('/').to_owned())
    }
}

#[cfg(test)]
mod tests {
    use pretty_assertions::assert_eq;

    use super::*;

    fn parse(content: &str) -> Result<Config, String> {
        toml::from_str(content).map_err(|e| e.to_string())
    }

    #[test]
    fn empty_file_gives_defaults() {
        let config = parse("").unwrap();
        assert_eq!(config.server.listen, "127.0.0.1:8000".parse().unwrap());
        assert!(config.server.read_only);
        assert_eq!(
            config.api.url.as_str(),
            "https://localhost/rudder/api/latest"
        );
        assert!(!config.api.tls_skip_verify);
        assert_eq!(
            config.rudderc.path,
            PathBuf::from("/opt/rudder/bin/rudderc")
        );
    }

    #[test]
    fn full_file() {
        let config = parse(
            r#"
            [server]
            listen = "0.0.0.0:9000"
            read_only = false
            [api]
            url = "https://rudder.example.com/rudder/api/latest/"
            tls_skip_verify = true
            [rudderc]
            path = "/usr/local/bin/rudderc"
            "#,
        )
        .unwrap();
        assert_eq!(config.server.listen, "0.0.0.0:9000".parse().unwrap());
        assert!(!config.server.read_only);
        // Trailing `/` removed, paths are appended with one
        assert_eq!(
            config.api.url.as_str(),
            "https://rudder.example.com/rudder/api/latest"
        );
        assert!(config.api.tls_skip_verify);
        assert_eq!(config.rudderc.path, PathBuf::from("/usr/local/bin/rudderc"));
    }

    #[test]
    fn rejects_unknown_keys() {
        let error = parse("[server]\nread_onyl = false\n").unwrap_err();
        assert!(error.contains("unknown field `read_onyl`"), "{error}");
        let error = parse("[logging]\nlevel = \"debug\"\n").unwrap_err();
        assert!(error.contains("unknown field `logging`"), "{error}");
    }

    #[test]
    fn rejects_non_https_api_url() {
        let error = parse("[api]\nurl = \"http://localhost/rudder/api/latest\"\n").unwrap_err();
        assert!(error.contains("must be an https URL"), "{error}");
        let error = parse("[api]\nurl = \"not a url\"\n").unwrap_err();
        assert!(error.contains("invalid URL"), "{error}");
    }

    #[test]
    fn rejects_invalid_listen_address() {
        let error = parse("[server]\nlisten = \"localhost\"\n").unwrap_err();
        assert!(error.contains("invalid socket address"), "{error}");
    }

    #[test]
    fn load_reports_missing_file() {
        let error = Config::load(Path::new("/nonexistent/rudder-mcp.conf")).unwrap_err();
        assert!(
            error
                .to_string()
                .contains("could not read configuration file /nonexistent/rudder-mcp.conf")
        );
    }
}
