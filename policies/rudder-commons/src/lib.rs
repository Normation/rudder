// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

#![allow(clippy::regex_creation_in_loops)]

pub mod logs;
pub mod methods;
pub mod report;

use anyhow::{Error, Result, anyhow, bail};
use serde::{Deserialize, Deserializer, Serialize};
use std::fmt::Display;
use std::{ffi::OsStr, fmt, path::Path, str::FromStr};

#[cfg(unix)]
pub const NODE_ID_PATH: &str = "/opt/rudder/etc/uuid.hive";
#[cfg(windows)]
pub const NODE_ID_PATH: &str = r"C:\Program Files\Rudder\etc\uuid.hive";

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
impl Display for Target {
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

#[derive(Copy, Clone, Debug, Serialize, Deserialize, Hash, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum ParameterFormat {
    Json,
    Yaml,
}

impl Display for ParameterFormat {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "{}",
            match self {
                ParameterFormat::Json => "JSON",
                ParameterFormat::Yaml => "YAML",
            }
        )
    }
}

impl ParameterFormat {
    pub fn validate(&self, s: &str) -> Result<()> {
        if s.trim().is_empty() {
            // Allow empty value for optional parameters
            return Ok(());
        }
        match self {
            ParameterFormat::Json => serde_json::from_str::<serde_json::Value>(s).map(|_| ())?,
            ParameterFormat::Yaml => serde_yaml::from_str::<serde_yaml::Value>(s).map(|_| ())?,
        }
        Ok(())
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
    #[serde(skip_serializing_if = "Option::is_none")]
    pub not_regex: Option<RegexConstraint>,
    pub max_length: usize,
    pub min_length: usize,
    pub valid_format: Option<ParameterFormat>,
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
            MethodConstraint::NotRegex(v) => {
                // Ensure valid regex
                //
                // We use look-around so the regex crate is not enough
                let _regex = fancy_regex::Regex::new(&v)?;
                self.not_regex = Some(RegexConstraint {
                    value: v,
                    error_message: None,
                });
            }
            MethodConstraint::MaxLength(v) => self.max_length = v,
            MethodConstraint::MinLength(v) => self.min_length = v,
            MethodConstraint::ValidFormat(f) => self.valid_format = Some(f),
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
                "value length ({}) exceeds max length ({})",
                value.len(),
                self.max_length
            )
        }
        if value.len() < self.min_length {
            bail!(
                "value length ({}) is lower than min length ({})",
                value.len(),
                self.min_length
            )
        }
        if let Some(r) = &self.regex {
            let regex = fancy_regex::Regex::new(&r.value)?;
            if !regex.is_match(value)? {
                bail!("value '{}' does not match regex '{}'", value, r.value)
            }
        }
        if let Some(r) = &self.not_regex {
            let regex = fancy_regex::Regex::new(&r.value)?;
            if regex.is_match(value)? {
                bail!("value '{}' does match forbidden regex '{}'", value, r.value)
            }
        }
        if let Some(s) = &self.select
            && !s.iter().any(|x| x.value == value)
        {
            bail!(
                "value '{}' not included in allowed set {:?}",
                value,
                s.iter().map(|s| s.value.clone()).collect::<Vec<String>>()
            )
        }
        if let Some(f) = &self.valid_format
            && let Err(e) = f.validate(value)
        {
            bail!(
                "value '{}' does match the expected format '{}': {:?}",
                value,
                f,
                e
            )
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
    NotRegex(String),
    MaxLength(usize),
    MinLength(usize),
    ValidFormat(ParameterFormat),
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
            not_regex: None,
            max_length: DEFAULT_MAX_PARAM_LENGTH,
            min_length: 0,
            valid_format: None,
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

impl PolicyMode {
    pub fn from_string<'de, D>(deserializer: D) -> Result<Option<PolicyMode>, D::Error>
    where
        D: Deserializer<'de>,
    {
        use serde::de::Error;
        String::deserialize(deserializer).and_then(|string| match string.as_ref() {
            "enforce" => Ok(Some(PolicyMode::Enforce)),
            "audit" => Ok(Some(PolicyMode::Audit)),
            "none" => Ok(None),
            _ => Err(Error::custom(format!(
                "Could not parse policy mode '{string}'"
            ))),
        })
    }
}

impl Display for PolicyMode {
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
            max_length: 3,
            min_length: 2,
            ..Default::default()
        };
        assert!(constraints.is_valid("4").is_err());
        assert!(constraints.is_valid("42").is_ok());
        assert!(constraints.is_valid("424").is_ok());
        assert!(constraints.is_valid("4242").is_err());

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

        let constraints = MethodConstraints {
            not_regex: Some(RegexConstraint {
                value: "^a+$".to_string(),
                error_message: None,
            }),
            ..Default::default()
        };
        assert!(constraints.is_valid("b").is_ok());
        assert!(constraints.is_valid("ab").is_ok());
        assert!(constraints.is_valid("a").is_err());
        assert!(constraints.is_valid("aa").is_err());
    }

    #[test]
    fn it_validates_format() {
        let constraints = MethodConstraints {
            valid_format: Some(ParameterFormat::Json),
            ..Default::default()
        };
        assert!(constraints.is_valid(r#"{"a": 42}"#).is_ok());
        assert!(constraints.is_valid(r#"{"a": "42"}"#).is_ok());
        assert!(constraints.is_valid(r#"{"a": "42""#).is_err());
        assert!(constraints.is_valid(r#"{"a": 42"#).is_err());
        assert!(constraints.is_valid(r#"{"a": 42,}"#).is_err());
        assert!(constraints.is_valid(r#"{"a": 42, "b": 42}"#).is_ok());
        assert!(constraints.is_valid(r#"{"a": 42, "b": 42,}"#).is_err());
        assert!(
            constraints
                .is_valid(r#"{"a": 42, "b": 42, "c": 42}"#)
                .is_ok()
        );
        assert!(
            constraints
                .is_valid(r#"{"a": 42, "b": 42, "c": 42,}"#)
                .is_err()
        );
        assert!(constraints.is_valid(r#""#).is_ok());
        assert!(constraints.is_valid(r#"  "#).is_ok());

        let constraints = MethodConstraints {
            valid_format: Some(ParameterFormat::Yaml),
            ..Default::default()
        };
        assert!(constraints.is_valid("a: 42").is_ok());
        assert!(constraints.is_valid("a: :: 34").is_err());
        assert!(constraints.is_valid("a: '42'").is_ok());
        assert!(constraints.is_valid("a: '42'").is_ok());
        assert!(constraints.is_valid("a: 42\nb: 42").is_ok());
        assert!(constraints.is_valid("a: 42\nb: 42\nc: 42").is_ok());
    }
}
