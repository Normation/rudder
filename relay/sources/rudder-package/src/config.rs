// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

use std::{fmt, fs::read_to_string, path::Path};

use anyhow::Result;
use serde::{Deserialize, Serialize};

const PUBLIC_REPO_URL: &str = "https://repository.rudder.io/plugins";
const PRIVATE_REPO_URL: &str = "https://download.rudder.io/plugins";

#[derive(Serialize, Deserialize, PartialEq, Eq)]
pub struct SecretString {
    #[serde(flatten)]
    value: String,
}

impl fmt::Debug for SecretString {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("[REDACTED String]")
    }
}

impl SecretString {
    pub fn new(value: String) -> Self {
        Self { value }
    }

    pub fn expose_secret(&self) -> &String {
        &self.value
    }
}

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

#[derive(Serialize, Deserialize, Debug, PartialEq, Eq)]
pub struct Credentials {
    username: String,
    password: SecretString,
}

#[derive(Serialize, Deserialize, Debug, PartialEq, Eq)]
pub struct Configuration {
    url: String,
    credentials: Option<Credentials>,
    proxy: Option<ProxyConfiguration>,
}

impl Configuration {
    fn parse(src: &str) -> Result<Self> {
        let parsed: RawConfiguration = serde_ini::from_str(src)?;
        Ok(Configuration::from(parsed))
    }

    pub fn read(path: &Path) -> Result<Self> {
        let c = read_to_string(path)?;
        Self::parse(&c)
    }
}

#[derive(Serialize, Deserialize, Debug, PartialEq, Eq)]
pub struct ProxyConfiguration {
    url: String,
    credentials: Option<Credentials>,
}

impl From<RawConfiguration> for Configuration {
    fn from(raw: RawConfiguration) -> Self {
        let r = raw.rudder;
        let credentials = match (r.username.is_empty(), r.password.is_empty()) {
            (false, false) => Some(Credentials {
                username: r.username,
                password: SecretString::new(r.password),
            }),
            _ => None,
        };
        let proxy_credentials = match (r.proxy_user.is_empty(), r.proxy_password.is_empty()) {
            (false, false) => Some(Credentials {
                username: r.proxy_user,
                password: SecretString::new(r.proxy_password),
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
        let url = if r.url.is_empty() {
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
    use crate::config::{Configuration, Credentials, ProxyConfiguration, SecretString};
    use pretty_assertions::assert_eq;
    use std::path::Path;

    #[test]
    fn it_parses_default_config_file() {
        let reference = Configuration {
            url: "https://download.rudder.io/plugins".to_string(),
            credentials: Some(Credentials {
                username: "user".to_string(),
                password: SecretString::new("password".to_string()),
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
                password: SecretString::new("password".to_string()),
            }),
            proxy: Some(ProxyConfiguration {
                url: "http://22.29.35.56".to_string(),
                credentials: Some(Credentials {
                    username: "mario".to_string(),
                    password: SecretString::new("daisy".to_string()),
                }),
            }),
        };
        let conf = Configuration::read(Path::new("./tests/config/rudder-pkg.proxy.conf")).unwrap();
        assert_eq!(reference, conf);
    }
}
