// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

use std::{ffi::OsStr, fmt, path::Path, str::FromStr};

use anyhow::{anyhow, bail, Error, Result};
use serde::{Deserialize, Deserializer, Serialize};

pub const ALL_TARGETS: &[Target] = &[Target::Unix, Target::Windows];

pub const DEFAULT_MAX_PARAM_LENGTH: usize = 16384;

/// Targets. A bit like (machine, vendor, operating-system) targets for system compiler like
/// "x86_64-unknown-linux-gnu", it depends on several items.
///
/// We can define our own (operating-system/platform, agent/management technology, environment) target spec.
#[derive(Debug, Copy, Clone, PartialEq, Eq, Serialize)]
pub enum Target {
    /// Actually (Unix, CFEngine + patches, system techniques + ncf)
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

/// Detect target from file extension
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

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum ParameterType {
    String,
    HereString,
}

impl fmt::Display for ParameterType {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "{}",
            match self {
                Self::String => "string",
                Self::HereString => "here-string",
            }
        )
    }
}

impl Default for ParameterType {
    fn default() -> Self {
        Self::String
    }
}

impl FromStr for ParameterType {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        Ok(match s {
            "String" => ParameterType::String,
            "HereString" => ParameterType::HereString,
            t => bail!("Unknown parameter type {}", t),
        })
    }
}

#[derive(Clone, Debug, Serialize, Deserialize, Hash, PartialEq, Eq)]
pub struct Constraints {
    pub allow_empty_string: bool,
    pub allow_whitespace_string: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub select: Option<Vec<String>>,
    // Storing as string to be able to ser/de easily
    #[serde(skip_serializing_if = "Option::is_none")]
    pub regex: Option<String>,
    pub max_length: usize,
}

impl Constraints {
    /// Update the set of constraints from given constraint
    pub fn update(&mut self, constraint: Constraint) -> Result<()> {
        match constraint {
            Constraint::AllowEmptyString(v) => self.allow_empty_string = v,
            Constraint::AllowWhitespaceString(v) => self.allow_whitespace_string = v,
            Constraint::Select(v) => self.select = Some(v),
            Constraint::Regex(v) => {
                // Ensure valid regex
                //
                // We use look-around so the regex crate is not enough
                let _regex = fancy_regex::Regex::new(&v)?;
                self.regex = Some(v);
            }
            Constraint::MaxLength(v) => self.max_length = v,
        }
        Ok(())
    }

    /// Validate if the given values matches the constraints set
    pub fn is_valid(&self, value: &str) -> Result<()> {
        if !self.allow_empty_string && value.is_empty() {
            bail!("value must not be empty");
        }
        // allow_whitespace_string allows empty string
        if !value.is_empty() && !self.allow_whitespace_string && value.trim().is_empty() {
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
            let regex = fancy_regex::Regex::new(r)?;
            if !regex.is_match(value)? {
                bail!("value '{}' does not match regex '{}'", value, r)
            }
        }
        if let Some(s) = &self.select {
            if !s.iter().any(|x| x == value) {
                bail!("value '{}' not included in allowed set {:?}", value, s)
            }
        }
        Ok(())
    }
}

#[derive(Clone, Debug, Serialize, Deserialize, Hash, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum Constraint {
    AllowEmptyString(bool),
    AllowWhitespaceString(bool),
    Select(Vec<String>),
    Regex(String),
    MaxLength(usize),
}

/// Default constraints
impl Default for Constraints {
    fn default() -> Self {
        Self {
            allow_empty_string: false,
            allow_whitespace_string: false,
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
        let constraints = Constraints::default();
        assert!(constraints.is_valid("").is_err());
        assert!(constraints.is_valid("    ").is_err());
        assert!(constraints.is_valid("42").is_ok());

        let constraints = Constraints {
            allow_empty_string: true,
            ..Default::default()
        };
        assert!(constraints.is_valid("").is_ok());
        assert!(constraints.is_valid("42").is_ok());
        assert!(constraints.is_valid(" ").is_err());

        let constraints = Constraints {
            max_length: 2,
            ..Default::default()
        };
        assert!(constraints.is_valid("4").is_ok());
        assert!(constraints.is_valid("42").is_ok());
        assert!(constraints.is_valid("424").is_err());

        let constraints = Constraints {
            select: Some(vec!["true".to_string(), "false".to_string()]),
            ..Default::default()
        };
        assert!(constraints.is_valid("true").is_ok());
        assert!(constraints.is_valid("false").is_ok());
        assert!(constraints.is_valid("").is_err());
        assert!(constraints.is_valid("fal").is_err());

        let constraints = Constraints {
            regex: Some("^[a-z]+$".to_string()),
            ..Default::default()
        };
        assert!(constraints.is_valid("a").is_ok());
        assert!(constraints.is_valid("correct").is_ok());
        assert!(constraints.is_valid("").is_err());
        assert!(constraints.is_valid("ae2er").is_err());
    }
}
