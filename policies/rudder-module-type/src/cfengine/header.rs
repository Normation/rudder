// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Normation SAS

//! Header of the CFEngine custom promise protocol

use std::{fmt, str::FromStr};

use anyhow::{bail, Error};

#[derive(Debug, PartialEq, Clone)]
pub(crate) struct Header {
    /// The name of the sender
    pub name: String,
    /// Version
    pub version: String,
    /// Flags
    pub flags: Vec<String>,
    /// Protocol version
    pub protocol_version: String,
}

impl fmt::Display for Header {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "{} {} {}",
            self.name, self.version, self.protocol_version
        )?;
        if !self.flags.is_empty() {
            write!(f, " {}", self.flags.join(" "))?;
        }
        Ok(())
    }
}

impl FromStr for Header {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        let header: Vec<&str> = s.splitn(4, ' ').collect();

        if header.len() < 3 {
            bail!("Incomplete header");
        }

        let name = header[0].to_string();
        let version = header[1].to_string();
        let protocol_version = header[2].to_string();
        let flags = match header.get(3) {
            Some(f) => f.split(' ').map(|s| s.to_string()).collect(),
            None => vec![],
        };

        Ok(Self {
            name,
            version,
            protocol_version,
            flags,
        })
    }
}

impl Header {
    pub(crate) fn compatibility(&self) -> Result<(), Error> {
        // Compatibility checks
        if !["CFEngine", "cf-agent"].contains(&self.name.as_str()) {
            bail!("Unknown agent {}, expecting 'CFEngine'", self.name);
        }
        if self.protocol_version != "v1" {
            bail!(
                "Incompatible protocol version {}, expecting v1",
                self.protocol_version
            );
        }
        if !self.flags.is_empty() {
            bail!("Expecting empty flags");
        }
        Ok(())
    }

    pub(crate) fn new(name: String, version: String) -> Self {
        Self {
            name,
            version,
            protocol_version: "v1".to_string(),
            // We always implement the JSON variant
            // And all modules MUST support audit mode
            flags: vec!["json_based".to_string(), "action_policy".to_string()],
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn it_parses_header() {
        assert_eq!(
            "CFEngine 3.16.0 v1".parse::<Header>().unwrap(),
            Header {
                name: "CFEngine".to_string(),
                version: "3.16.0".to_string(),
                protocol_version: "v1".to_string(),
                flags: vec![],
            }
        );
    }

    #[test]
    fn it_displays_header() {
        assert_eq!(
            "CFEngine 3.16.0 v1",
            Header {
                name: "CFEngine".to_string(),
                version: "3.16.0".to_string(),
                protocol_version: "v1".to_string(),
                flags: vec![],
            }
            .to_string()
        );

        assert_eq!(
            "git_promise_module 0.0.1 v1 json_based",
            Header {
                name: "git_promise_module".to_string(),
                version: "0.0.1".to_string(),
                protocol_version: "v1".to_string(),
                flags: vec!["json_based".to_string()],
            }
            .to_string()
        );
    }
}
