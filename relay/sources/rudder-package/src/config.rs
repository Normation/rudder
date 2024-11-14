// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

use std::{fs::read_to_string, path::Path};

use anyhow::Result;
use secrecy::SecretString;
use serde::Deserialize;
use tracing::info;

const PUBLIC_REPO_URL: &str = "https://repository.rudder.io/plugins";
const PRIVATE_REPO_URL: &str = "https://download.rudder.io/plugins";

/// Wrapper as the default config has a "Rudder" section
#[derive(Deserialize, Debug, PartialEq, Eq)]
#[serde(rename_all = "PascalCase")]
struct RawConfiguration {
    #[serde(default)]
    rudder: RudderSection,
}

// Note, "key = " lines produce Some("") when using Option
// So let's use String everywhere and clean afterwards.
#[derive(Deserialize, Debug, PartialEq, Eq, Default)]
struct RudderSection {
    #[serde(default)]
    url: String,
    #[serde(default)]
    username: String,
    #[serde(default)]
    password: String,
    #[serde(default)]
    proxy_url: String,
    #[serde(default)]
    proxy_user: String,
    #[serde(default)]
    proxy_password: String,
}

#[derive(Deserialize, Clone, Debug)]
pub struct Credentials {
    pub username: String,
    pub password: SecretString,
}

impl PartialEq for Credentials {
    fn eq(&self, other: &Self) -> bool {
        self.username == other.username
    }
}

#[derive(Deserialize, Debug, PartialEq)]
pub struct Configuration {
    pub url: String,
    pub credentials: Option<Credentials>,
    pub proxy: Option<ProxyConfiguration>,
}

impl Configuration {
    pub fn parse(src: &str) -> Result<Self> {
        let parsed: RawConfiguration = serde_ini::from_str(src)?;
        Ok(Configuration::from(parsed))
    }

    pub fn read(path: &Path) -> Result<Self> {
        let c = if path.exists() {
            read_to_string(path)?
        } else {
            info!(
                "'{}' does not exist, using default configuration",
                path.display()
            );
            "".to_string()
        };
        Self::parse(&c)
    }
}

#[derive(Deserialize, Debug, PartialEq)]
pub struct ProxyConfiguration {
    pub url: String,
    pub credentials: Option<Credentials>,
}

impl From<RawConfiguration> for Configuration {
    fn from(raw: RawConfiguration) -> Self {
        let r = raw.rudder;
        let credentials = match (r.username.is_empty(), r.password.is_empty()) {
            (false, false) => Some(Credentials {
                username: r.username,
                password: r.password.into(),
            }),
            _ => None,
        };
        let proxy_credentials = match (r.proxy_user.is_empty(), r.proxy_password.is_empty()) {
            (false, false) => Some(Credentials {
                username: r.proxy_user,
                password: r.proxy_password.into(),
            }),
            _ => None,
        };
        let proxy = match (r.proxy_url.is_empty(), proxy_credentials) {
            (false, credentials) => Some(ProxyConfiguration {
                url: r.proxy_url,
                credentials,
            }),
            _ => None,
        };
        // Also fallback to public repo if no credentials and private repo is configured - this was the previous default config
        let url = if r.url.is_empty() || r.url.starts_with(PRIVATE_REPO_URL) {
            if credentials.is_some() {
                PRIVATE_REPO_URL.to_owned()
            } else {
                PUBLIC_REPO_URL.to_owned()
            }
        } else {
            r.url
        };
        Self {
            url,
            credentials,
            proxy,
        }
    }
}

#[cfg(test)]
mod tests {
    use std::path::Path;

    use pretty_assertions::assert_eq;

    use crate::config::{Configuration, Credentials, ProxyConfiguration};

    #[test]
    fn it_parses_default_config_file() {
        let reference = Configuration {
            url: "https://download.rudder.io/plugins".to_string(),
            credentials: Some(Credentials {
                username: "user".to_string(),
                password: "password".into(),
            }),
            proxy: None,
        };
        let conf = Configuration::read(Path::new("./tests/config/rudder-pkg.conf")).unwrap();
        assert_eq!(reference, conf);
    }
    #[test]
    fn it_parses_empty_config_file() {
        let reference = Configuration {
            url: "https://repository.rudder.io/plugins".to_string(),
            credentials: None,
            proxy: None,
        };
        let conf = Configuration::parse("").unwrap();
        assert_eq!(reference, conf);
    }
    #[test]
    fn it_parses_full_config_file() {
        let reference = Configuration {
            url: "https://download2.rudder.io/plugins".to_string(),
            credentials: Some(Credentials {
                username: "user".to_string(),
                password: "password".into(),
            }),
            proxy: Some(ProxyConfiguration {
                url: "http://22.29.35.56".to_string(),
                credentials: Some(Credentials {
                    username: "mario".to_string(),
                    password: "password".into(),
                }),
            }),
        };
        let conf = Configuration::read(Path::new("./tests/config/rudder-pkg.proxy.conf")).unwrap();
        assert_eq!(reference, conf);
    }
}
