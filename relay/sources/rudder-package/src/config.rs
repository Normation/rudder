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
    use secrecy::ExposeSecret;

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

    // `rudder-pkg.conf` is read both in Rust here and in Scala the webapp part.
    // We must ensure that the parsing (and esp. escaping rules) are the same between the two
    // parsers, so we use the `rudder-pkg.tricky.conf` file.
    // A change here or in the data file needs to be mirrored in
    // `webapp/sources/utils/src/test/scala/com/normation/utils/IniTest.scala`.

    // no escape for backslash and `#`, `;` and `=` are ordinary characters inside a value
    #[test]
    fn it_reads_values_verbatim_without_unescaping() {
        let conf = Configuration::read(Path::new("./tests/config/rudder-pkg.tricky.conf")).unwrap();

        let credentials = conf.credentials.expect("credentials must be parsed");
        assert_eq!(credentials.username, "user-é-ü");
        assert_eq!(
            credentials.password.expose_secret(),
            r#"p@ss\w0rd\\next#hash;semi=equals "quoted" spaced"#
        );

        let proxy = conf.proxy.expect("proxy must be parsed");
        let proxy_credentials = proxy.credentials.expect("proxy credentials must be parsed");
        assert_eq!(proxy_credentials.username, "mario");
        assert_eq!(proxy_credentials.password.expose_secret(), "mot-de-passe-é");
    }

    // Only keys and values are trimmed. A section name is taken verbatim between the
    // brackets, and the line itself is not trimmed before being looked at, so an
    // indented header or comment is not a valid section.
    #[test]
    fn it_does_not_trim_section_names() {
        // `[ Rudder ]` declares a section named " Rudder ", which is not the `Rudder` field,
        // so the credentials are ignored
        let conf =
            Configuration::parse("[ Rudder ]\nusername = user\npassword = s3cret\n").unwrap();
        assert_eq!(conf.credentials, None);
        assert_eq!(conf.url, "https://repository.rudder.io/plugins");
    }

    #[test]
    fn it_rejects_an_indented_section_header() {
        assert!(Configuration::parse("  [Rudder]\nusername = user\n").is_err());
    }

    #[test]
    fn it_rejects_an_indented_comment() {
        assert!(Configuration::parse("[Rudder]\n  # a comment\n").is_err());
    }

    #[test]
    fn it_rejects_a_blank_but_not_empty_line() {
        assert!(Configuration::parse("[Rudder]\n \nusername = user\n").is_err());
        // an actually empty line is fine
        assert!(Configuration::parse("[Rudder]\n\nusername = user\n").is_ok());
    }

    #[test]
    fn it_rejects_a_section_name_holding_a_closing_bracket() {
        assert!(Configuration::parse("[Rud]der]\n").is_err());
    }

    // password are trimmed of all whitespace chars
    #[test]
    fn it_trims_whitespace_around_keys_and_values() {
        let conf = Configuration::parse("[Rudder]\n  username\t=\tuser  \n\tpassword =  s3cret \n")
            .unwrap();

        let credentials = conf.credentials.expect("credentials must be parsed");
        assert_eq!(credentials.username, "user");
        assert_eq!(credentials.password.expose_secret(), "s3cret");
    }

    #[test]
    fn it_trims_the_unicode_definition_of_whitespace() {
        // `str::trim` follows the Unicode White_Space property, which includes the
        // non-breaking spaces that Java's `Character.isWhitespace` deliberately excludes
        let conf =
            Configuration::parse("[Rudder]\nusername = user\npassword =\u{00A0}s3cret\u{202F}\n")
                .unwrap();

        let credentials = conf.credentials.expect("credentials must be parsed");
        assert_eq!(credentials.password.expose_secret(), "s3cret");
    }
}
