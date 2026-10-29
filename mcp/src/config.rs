// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

//! Configuration directory, as relayd: `main.conf` and `logging.conf` (TOML), the latter read
//! with `rudder_cli::logs::daemon`.
//!
//! Every setting has a default, so an empty file is valid. Unknown keys are rejected to catch
//! typos.

use std::{net::SocketAddr, path::Path};

use anyhow::{Context, Result};
use rudder_cli::logs::daemon::{LogConfig, LogFilter};
use serde::{Deserialize, de::DeserializeOwned};
use url::{Host, Url};

/// Reads a TOML configuration file
fn load<T: DeserializeOwned>(path: &Path) -> Result<T> {
    let content = std::fs::read_to_string(path)
        .with_context(|| format!("could not read configuration file {}", path.display()))?;
    toml::from_str(&content)
        .with_context(|| format!("invalid configuration file {}", path.display()))
}

/// Log filter when `logging.conf` sets none. rmcp's `info` logs on each request bury the request
/// log.
const DEFAULT_LOG_FILTER: &str = "rmcp=warn";

/// Reads `logging.conf`
pub fn load_log_config(dir: &Path) -> Result<LogConfig> {
    let mut config = LogConfig::load(dir)?;
    if config.general.filter == LogFilter::default() {
        config.general.filter = LogFilter::try_from(DEFAULT_LOG_FILTER.to_owned())
            .expect("the default log filter is valid");
    }
    Ok(config)
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

/// Rudder server URL, possibly with a path prefix (reverse proxy) or a query. Plain http only to the
/// local server, as API tokens are sent to it.
#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(try_from = "String")]
pub struct ServerUrl(Url);

impl ServerUrl {
    /// Base URL of the Rudder API on this server
    pub fn api_url(&self) -> Url {
        let mut url = self.0.clone();
        url.path_segments_mut()
            .expect("http(s) URLs have a path")
            .pop_if_empty()
            .extend(["rudder", "api", "latest"]);
        url
    }
}

impl Default for ServerUrl {
    fn default() -> Self {
        Self(Url::parse("http://127.0.0.1:8080").expect("the default URL is valid"))
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
        // The configuration is world-readable, and the URL is logged
        if !parsed.username().is_empty() || parsed.password().is_some() {
            return Err(format!("must not contain credentials, got '{url}'"));
        }
        Ok(Self(parsed))
    }
}

#[cfg(test)]
mod tests {
    use pretty_assertions::assert_eq;

    use super::*;

    fn parse(content: &str) -> Result<Config, String> {
        toml::from_str(content).map_err(|e| e.to_string())
    }

    fn api_url(server_url: &str) -> Result<String, String> {
        ServerUrl::try_from(server_url.to_owned()).map(|url| url.api_url().to_string())
    }

    #[test]
    fn empty_file_gives_defaults() {
        let config = parse("").unwrap();
        assert_eq!(config.server.listen, "127.0.0.1:3031".parse().unwrap());
        assert_eq!(
            config.api.url.api_url().as_str(),
            "http://127.0.0.1:8080/rudder/api/latest"
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
        assert_eq!(
            config.api.url.api_url().as_str(),
            "https://rudder.example.com/rudder/api/latest"
        );
    }

    #[test]
    fn plain_http_only_to_local_server() {
        for url in [
            "http://localhost:8080",
            "http://127.0.0.1:8080",
            "http://[::1]:8080",
        ] {
            assert!(api_url(url).is_ok(), "{url}");
        }
        let error = parse("[api]\nurl = \"http://rudder.example.com\"\n").unwrap_err();
        assert!(error.contains("must be an https URL"), "{error}");
        let error = parse("[api]\nurl = \"ftp://127.0.0.1\"\n").unwrap_err();
        assert!(error.contains("must be an http(s) URL"), "{error}");
        let error = parse("[api]\nurl = \"not a url\"\n").unwrap_err();
        assert!(error.contains("invalid URL"), "{error}");
    }

    #[test]
    fn api_url_keeps_path_prefix_and_query() {
        for (server_url, expected) in [
            (
                "https://rudder.example.com:8443",
                "https://rudder.example.com:8443/rudder/api/latest",
            ),
            (
                "https://proxy.example.com/prefix/",
                "https://proxy.example.com/prefix/rudder/api/latest",
            ),
            (
                "https://proxy.example.com/prefix?site=paris",
                "https://proxy.example.com/prefix/rudder/api/latest?site=paris",
            ),
        ] {
            assert_eq!(api_url(server_url).unwrap(), expected);
        }
    }

    #[test]
    fn rejects_credentials_in_server_url() {
        for url in [
            "https://user:password@rudder.example.com",
            "https://user@rudder.example.com",
        ] {
            let error = api_url(url).unwrap_err();
            assert!(
                error.contains("must not contain credentials"),
                "{url}: {error}"
            );
        }
    }

    #[test]
    fn sample_files_are_valid() {
        let dir = Path::new(concat!(env!("CARGO_MANIFEST_DIR"), "/config"));
        Config::load(dir).unwrap();
        load_log_config(dir).unwrap();
    }

    #[test]
    fn rmcp_logs_only_warnings_by_default() {
        let dir = Path::new(concat!(env!("CARGO_MANIFEST_DIR"), "/config"));
        assert_eq!(load_log_config(dir).unwrap().to_string(), "info,rmcp=warn");
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
