// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

//! Configuration directory, as relayd: `main.conf` and `logging.conf` (TOML), the latter read
//! with `rudder_cli::logs::daemon`.
//!
//! Every setting has a default, so an empty file is valid. Unknown keys are rejected to catch
//! typos.

use std::{net::SocketAddr, path::Path};

use anyhow::{Context, Result};
use serde::{Deserialize, de::DeserializeOwned};
use url::{Host, Url};

/// Reads a TOML configuration file
fn load<T: DeserializeOwned>(path: &Path) -> Result<T> {
    let content = std::fs::read_to_string(path)
        .with_context(|| format!("could not read configuration file {}", path.display()))?;
    toml::from_str(&content)
        .with_context(|| format!("invalid configuration file {}", path.display()))
}

/// Content of `main.conf`
#[derive(Debug, Default, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Config {
    pub server: ServerConfig,
    pub api: ApiConfig,
}

impl Config {
    pub fn load(dir: &Path) -> Result<Self> {
        load(&dir.join("main.conf"))
    }
}

#[derive(Debug, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct ServerConfig {
    /// Address the MCP endpoint listens on
    pub listen: SocketAddr,
}

impl Default for ServerConfig {
    fn default() -> Self {
        Self {
            listen: SocketAddr::from(([127, 0, 0, 1], 3031)),
        }
    }
}

#[derive(Debug, Default, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct ApiConfig {
    /// Rudder server URL
    pub url: ServerUrl,
}

/// Rudder server URL, without trailing `/`. Plain http only to the local server, as API tokens are
/// sent to it.
#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(try_from = "String")]
pub struct ServerUrl(String);

impl Default for ServerUrl {
    fn default() -> Self {
        Self("http://127.0.0.1:8080".to_owned())
    }
}

impl TryFrom<String> for ServerUrl {
    type Error = String;

    fn try_from(url: String) -> Result<Self, Self::Error> {
        let parsed = Url::parse(&url).map_err(|e| format!("invalid URL '{url}': {e}"))?;
        let local = match parsed.host() {
            Some(Host::Ipv4(ip)) => ip.is_loopback(),
            Some(Host::Ipv6(ip)) => ip.is_loopback(),
            Some(Host::Domain(domain)) => domain == "localhost",
            None => false,
        };
        match parsed.scheme() {
            "https" => (),
            "http" if local => (),
            "http" => {
                return Err(format!(
                    "must be an https URL for a remote server, got '{url}'"
                ));
            }
            _ => return Err(format!("must be an http(s) URL, got '{url}'")),
        }
        Ok(Self(parsed.as_str().trim_end_matches('/').to_owned()))
    }
}

#[cfg(test)]
mod tests {
    use pretty_assertions::assert_eq;
    use rudder_cli::logs::daemon::LogConfig;

    use super::*;

    fn parse(content: &str) -> Result<Config, String> {
        toml::from_str(content).map_err(|e| e.to_string())
    }

    #[test]
    fn empty_file_gives_defaults() {
        let config = parse("").unwrap();
        assert_eq!(config.server.listen, "127.0.0.1:3031".parse().unwrap());
        assert_eq!(
            config.api.url,
            ServerUrl("http://127.0.0.1:8080".to_owned())
        );
    }

    #[test]
    fn full_file() {
        let config = parse(
            r#"
            [server]
            listen = "0.0.0.0:9000"
            [api]
            url = "https://rudder.example.com/"
            "#,
        )
        .unwrap();
        assert_eq!(config.server.listen, "0.0.0.0:9000".parse().unwrap());
        // Trailing `/` removed, paths are appended with one
        assert_eq!(
            config.api.url,
            ServerUrl("https://rudder.example.com".to_owned())
        );
    }

    #[test]
    fn plain_http_only_to_local_server() {
        for url in [
            "http://localhost:8080",
            "http://127.0.0.1:8080",
            "http://[::1]:8080",
        ] {
            assert_eq!(
                ServerUrl::try_from(url.to_owned()).unwrap(),
                ServerUrl(url.to_owned())
            );
        }
        let error = parse("[api]\nurl = \"http://rudder.example.com\"\n").unwrap_err();
        assert!(error.contains("must be an https URL"), "{error}");
        let error = parse("[api]\nurl = \"ftp://127.0.0.1\"\n").unwrap_err();
        assert!(error.contains("must be an http(s) URL"), "{error}");
        let error = parse("[api]\nurl = \"not a url\"\n").unwrap_err();
        assert!(error.contains("invalid URL"), "{error}");
    }

    #[test]
    fn sample_files_are_valid() {
        let dir = Path::new(concat!(env!("CARGO_MANIFEST_DIR"), "/config"));
        Config::load(dir).unwrap();
        LogConfig::load(dir).unwrap();
    }

    #[test]
    fn rejects_unknown_keys() {
        let error = parse("[server]\nlisen = \"0.0.0.0:9000\"\n").unwrap_err();
        assert!(error.contains("unknown field `lisen`"), "{error}");
        let error = parse("[metrics]\nenabled = true\n").unwrap_err();
        assert!(error.contains("unknown field `metrics`"), "{error}");
    }

    #[test]
    fn rejects_invalid_listen_address() {
        let error = parse("[server]\nlisten = \"localhost\"\n").unwrap_err();
        assert!(error.contains("invalid socket address"), "{error}");
    }

    #[test]
    fn load_reports_missing_file() {
        let error = Config::load(Path::new("/nonexistent")).unwrap_err();
        assert!(
            error
                .to_string()
                .contains("could not read configuration file /nonexistent/main.conf")
        );
    }
}
