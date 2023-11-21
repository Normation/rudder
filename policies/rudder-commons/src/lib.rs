// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

pub mod logs;
pub mod methods;
pub mod report;

use std::{ffi::OsStr, fmt, path::Path, str::FromStr};

use anyhow::{anyhow, bail, Error, Result};
use serde::{Deserialize, Deserializer, Serialize};

pub const ALL_TARGETS: &[Target] = &[Target::Unix, Target::Windows];

pub const DEFAULT_MAX_PARAM_LENGTH: usize = 16384;

/// We want to only compile the regex once
#[macro_export]
macro_rules! regex_comp {
    ($re:literal $(,)?) => {{
        static RE: std::sync::OnceLock<regex::Regex> = std::sync::OnceLock::new();
        RE.get_or_init(|| regex::Regex::new($re).unwrap())
    }};
}

/// Targets. A bit like (machine, vendor, operating-system) targets for system compiler like
/// "x86_64-unknown-linux-gnu", it depends on several items.
///
/// We can define our own (operating-system/platform, agent/management technology, environment) target spec.
#[derive(Debug, Copy, Clone, PartialEq, Eq, Serialize, Default)]
pub enum Target {
    /// Actually (Unix, CFEngine + patches, system techniques + ncf)
    #[default]
    Unix,
    /// Actually (Windows, Dotnet + DSC, )
    Windows,
}

impl Target {
    /// Extension of the output files
    pub fn extension(self) -> &'static str {
        match self {
            Self::Unix => "cf",
            Self::Windows => "ps1",
        }
    }
}

/// Detect target from a file extension
impl TryFrom<&Path> for Target {
    type Error = Error;

    fn try_from(file: &Path) -> core::result::Result<Self, Self::Error> {
        let extension = file
            .extension()
            .and_then(OsStr::to_str)
            .ok_or_else(|| anyhow!("Could not read file extension as UTF-8"))?;

        extension.parse()
    }
}

// standard target name
impl fmt::Display for Target {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "{}",
            match self {
                Target::Unix => "Linux",
                Target::Windows => "Windows",
            }
        )
    }
}

impl FromStr for Target {
    type Err = Error;

    fn from_str(target: &str) -> Result<Self> {
        match target {
            "cf" | "cfengine" | "CFEngine" | "unix" | "Unix" => Ok(Target::Unix),
            "ps1" | "powershell" | "dsc" | "windows" | "Windows" => Ok(Target::Windows),
            "yml" | "yaml" => bail!("YAML is not a valid target format but only a source"),
            _ => bail!("Could not recognize target {:?}", target),
        }
    }
}

impl<'de> Deserialize<'de> for Target {
    fn deserialize<D>(deserializer: D) -> core::result::Result<Self, D::Error>
    where
        D: Deserializer<'de>,
    {
        let fmt = String::deserialize(deserializer)?;
        Target::from_str(&fmt).map_err(serde::de::Error::custom)
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, Default, PartialOrd, Ord)]
#[serde(rename_all = "lowercase")]
pub enum Escaping {
    Raw,
    #[serde(rename = "here-string")]
    HereString,
    #[default]
    String,
}

impl FromStr for Escaping {
    type Err = Error;

    fn from_str(s: &str) -> std::result::Result<Self, Self::Err> {
        Ok(match s.to_lowercase().as_str() {
            "string" => Self::String,
            "herestring" | "here-string" => Self::HereString,
            "raw" | "none" => Self::Raw,
            _ => bail!("Unrecognized escaping value: {}", s),
        })
    }
}

#[derive(Clone, Debug, Serialize, Deserialize, Hash, PartialEq, Eq)]
pub struct MethodConstraints {
    #[serde(rename = "allow_empty_string")]
    pub allow_empty: bool,
    #[serde(rename = "allow_whitespace_string")]
    pub allow_whitespace: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub select: Option<Vec<Select>>,
    // Storing as string to be able to ser/de easily
    #[serde(skip_serializing_if = "Option::is_none")]
    pub regex: Option<RegexConstraint>,
    pub max_length: usize,
}

impl MethodConstraints {
    /// Update the set of constraints from given constraint
    pub fn update(&mut self, constraint: MethodConstraint) -> Result<()> {
        match constraint {
            MethodConstraint::AllowEmpty(v) => self.allow_empty = v,
            MethodConstraint::AllowWhitespace(v) => self.allow_whitespace = v,
            MethodConstraint::Select(v) => {
                self.select = Some(
                    v.into_iter()
                        .map(|s| Select {
                            name: None,
                            value: s,
                        })
                        .collect(),
                )
            }
            MethodConstraint::Regex(v) => {
                // Ensure valid regex
                //
                // We use look-around so the regex crate is not enough
                let _regex = fancy_regex::Regex::new(&v)?;
                self.regex = Some(RegexConstraint {
                    value: v,
                    error_message: None,
                });
            }
            MethodConstraint::MaxLength(v) => self.max_length = v,
        }
        Ok(())
    }

    /// Validate if the given values matches the constraints set
    pub fn is_valid(&self, value: &str) -> Result<()> {
        if !self.allow_empty && value.is_empty() {
            bail!("value must not be empty");
        }
        // allow_whitespace allows empty string
        if !value.is_empty() && !self.allow_whitespace && value.trim().is_empty() {
            bail!("value must not be only whitespaces");
        }
        if value.len() > self.max_length {
            bail!(
                "value length ({}) exceeds max value ({})",
                value.len(),
                self.max_length
            )
        }
        if let Some(r) = &self.regex {
            let regex = fancy_regex::Regex::new(&r.value)?;
            if !regex.is_match(value)? {
                bail!("value '{}' does not match regex '{}'", value, r.value)
            }
        }
        if let Some(s) = &self.select {
            if !s.iter().any(|x| x.value == value) {
                bail!(
                    "value '{}' not included in allowed set {:?}",
                    value,
                    s.iter().map(|s| s.value.clone()).collect::<Vec<String>>()
                )
            }
        }
        Ok(())
    }
}

#[derive(Clone, Debug, Serialize, Deserialize, Hash, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum MethodConstraint {
    #[serde(rename = "allow_empty_string")]
    AllowEmpty(bool),
    #[serde(rename = "allow_whitespace_string")]
    AllowWhitespace(bool),
    Select(Vec<String>),
    Regex(String),
    MaxLength(usize),
}

#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize, Hash)]
#[serde(rename_all = "snake_case")]
pub struct Select {
    /// Human-readable name. If `None`, use the value.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    pub value: String,
}

#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize, Hash)]
#[serde(rename_all = "snake_case")]
pub struct RegexConstraint {
    pub value: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error_message: Option<String>,
}

/// Default constraints
impl Default for MethodConstraints {
    fn default() -> Self {
        Self {
            allow_empty: false,
            allow_whitespace: false,
            select: None,
            regex: None,
            max_length: DEFAULT_MAX_PARAM_LENGTH,
        }
    }
}

/// Canonify a string in the Rudder way, i.e. an underscore for each character
/// be it single or multibyte.
pub fn canonify(input: &str) -> String {
    input
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || c == '_' {
                c
            } else {
                '_'
            }
        })
        .collect()
}

pub fn is_canonified(input: &str) -> bool {
    input.chars().all(|x| x.is_ascii_alphanumeric() || x == '_')
}

#[derive(Debug, PartialEq, Eq, Serialize, Deserialize, Clone, Copy, Default)]
#[serde(rename_all = "lowercase")]
pub enum PolicyMode {
    #[default]
    Enforce,
    Audit,
}

impl fmt::Display for PolicyMode {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "{}",
            match self {
                PolicyMode::Enforce => "enforce",
                PolicyMode::Audit => "audit",
            }
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn it_canonifies() {
        assert_eq!(canonify(""), "".to_string());
        assert_eq!(canonify("abc"), "abc".to_string());
        assert_eq!(canonify("a-bc"), "a_bc".to_string());
        assert_eq!(canonify("a_bc"), "a_bc".to_string());
        assert_eq!(canonify("a bc"), "a_bc".to_string());
        assert_eq!(canonify("aàbc"), "a_bc".to_string());
        assert_eq!(canonify("a&bc"), "a_bc".to_string());
        assert_eq!(canonify("a9bc"), "a9bc".to_string());
        assert_eq!(canonify("a😋bc"), "a_bc".to_string());
    }

    #[test]
    fn it_checks_constraints() {
        let constraints = MethodConstraints::default();
        assert!(constraints.is_valid("").is_err());
        assert!(constraints.is_valid("    ").is_err());
        assert!(constraints.is_valid("42").is_ok());

        let constraints = MethodConstraints {
            allow_empty: true,
            ..Default::default()
        };
        assert!(constraints.is_valid("").is_ok());
        assert!(constraints.is_valid("42").is_ok());
        assert!(constraints.is_valid(" ").is_err());

        let constraints = MethodConstraints {
            max_length: 2,
            ..Default::default()
        };
        assert!(constraints.is_valid("4").is_ok());
        assert!(constraints.is_valid("42").is_ok());
        assert!(constraints.is_valid("424").is_err());

        let constraints = MethodConstraints {
            select: Some(vec![
                Select {
                    value: "true".to_string(),
                    name: None,
                },
                Select {
                    value: "false".to_string(),
                    name: None,
                },
            ]),
            ..Default::default()
        };
        assert!(constraints.is_valid("true").is_ok());
        assert!(constraints.is_valid("false").is_ok());
        assert!(constraints.is_valid("").is_err());
        assert!(constraints.is_valid("fal").is_err());

        let constraints = MethodConstraints {
            regex: Some(RegexConstraint {
                value: "^[a-z]+$".to_string(),
                error_message: None,
            }),
            ..Default::default()
        };
        assert!(constraints.is_valid("a").is_ok());
        assert!(constraints.is_valid("correct").is_ok());
        assert!(constraints.is_valid("").is_err());
        assert!(constraints.is_valid("ae2er").is_err());
    }
}
