// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

//! Models of the classic "ncf" methods of Rudder
//!
//! Uses a method (function-like) based model.

// Parseur de metadata à partir d'un .cf
// C'est quoi l'interface d'une generic method ?
// Faire la conversion vers une ressource

// transformer en "native resources" qui sont aussi définissables
// à la main

use std::{collections::HashMap, path::PathBuf, str::FromStr};

use anyhow::{bail, Error, Result};
use log::debug;
use serde::{Deserialize, Serialize};

use crate::Target;

pub type MethodName = String;
pub type AttributeName = String;

/// metadata about a "ncf" CFEngine/DSC method
///
/// Leaf yaml implemented by ncf
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, Default)]
pub struct Method {
    pub name: MethodName,
    pub description: String,
    /// Markdown formatted documentation
    pub documentation: String,
    // FIXME
    pub supported_targets: Vec<Target>,
    pub class_prefix: String,
    pub class_parameter: String,
    pub source: Option<PathBuf>,
    pub deprecated: Option<String>,
    /// Renamed method
    // FIXME separate struct for this?
    pub rename_to: Option<MethodName>,
    /// There can be a message about the cause
    pub action: Option<String>,
    pub method_name: String,
    pub method_args: Vec<String>,
    pub parameters: HashMap<AttributeName, Parameter>,
}

impl Method {
    // There is no specific builder type but let's treat it as is
    //
    // Check validity and clean thing up (trim, etc.)
    fn build(mut self) -> Result<Self> {
        self.name = self.name.trim().to_string();
        self.description = self.description.trim().to_string();
        self.documentation = self.documentation.trim().to_string();
        if let Some(deprecated) = self.deprecated.as_mut() {
            *deprecated = deprecated.trim().to_string();
        }

        if self.name.is_empty() {
            bail!("Empty method name")
        }
        if self.description.is_empty() {
            bail!("Empty method description")
        }
        if self.class_parameter.is_empty() {
            bail!("Empty class parameter description")
        }
        Ok(self)
    }
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Parameter {
    pub description: String,
    pub constraints: Constraints,
    pub renamed_to: Option<AttributeName>,
    pub p_type: ParameterType,
}

#[derive(Clone, Copy, Debug, PartialEq, Serialize, Deserialize)]
pub enum ParameterType {
    String,
    HereString,
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

impl Default for Parameter {
    fn default() -> Self {
        Self {
            description: "".to_string(),
            constraints: Constraints::default(),
            renamed_to: None,
            p_type: ParameterType::String,
        }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize, Hash, PartialEq, Eq)]
pub struct Constraints {
    pub allow_empty_string: bool,
    pub allow_whitespace_string: bool,
    pub select: Option<Vec<String>>,
    // Storing as string to be able to ser/de easily
    pub regex: Option<String>,
    pub max_length: usize,
}

impl Constraints {
    /// Update the set of constraints from given constraint
    fn update(&mut self, constraint: Constraint) -> Result<()> {
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
        if !self.allow_whitespace_string && value.trim().is_empty() {
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
            max_length: 16384,
        }
    }
}

/// Parser

/// We want to only compile the regex once
///
/// Use once_cell as showed in its documentation
/// https://docs.rs/once_cell/1.2.0/once_cell/index.html#building-block
macro_rules! regex {
    ($re:literal $(,)?) => {{
        static RE: once_cell::sync::OnceCell<regex::Regex> = once_cell::sync::OnceCell::new();
        RE.get_or_init(|| regex::Regex::new($re).unwrap())
    }};
}

impl FromStr for Method {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        let mut method = Method::default();
        // Kept the same logic and regexes as ncf.py for two big reasons:
        //
        // * compatibility
        // * laziness

        // Store current multiline tag
        let mut multiline = None;

        for line in s.lines() {
            // Parameter tags (with a parameter sub key)
            let attributes_tag_re =
                regex!(r"^\s*#\s*@(parameter\w*)\s*(([a-zA-Z0-9_]+)?\s+(.*?)|.*?)\s*$");
            if let Some(caps) = attributes_tag_re.captures(line) {
                let tag = &caps[1];
                let parameter_name = &caps[3];
                let parameter = method
                    .parameters
                    .entry(parameter_name.to_string())
                    .or_insert_with(Parameter::default);

                match tag {
                    "parameter" => parameter.description = caps[4].to_string(),
                    "parameter_constraint" => {
                        let constraint: Constraint = serde_json::from_str(&format!(
                            "{{ {} }}",
                            caps[4].replace('\\', "\\\\")
                        ))?;
                        parameter.constraints.update(constraint)?;
                    }
                    "parameter_type" => parameter.p_type = caps[4].parse()?,
                    "parameter_rename" => parameter.renamed_to = Some(caps[4].to_string()),
                    _ => debug!("Unknown tag {}", tag),
                }
                continue;
            }

            // Other tags, allow multiline
            let tag_re = regex!(r"^\s*#\s*@(\w+)\s*(([a-zA-Z0-9_]+)?\s+(.*?)|.*?)\s*$");
            if let Some(caps) = tag_re.captures(line) {
                let tag = &caps[1];
                match tag {
                    "name" => method.name = caps[2].to_string(),
                    "description" => {
                        method.description = caps[2].to_string();
                        multiline = Some("description");
                    }
                    "documentation" => {
                        method.documentation = caps[2].to_string();
                        multiline = Some("documentation");
                    }
                    "class_prefix" => method.class_prefix = caps[2].to_string(),
                    "class_parameter" => method.class_parameter = caps[2].to_string(),
                    "deprecated" => {
                        method.deprecated = Some(caps[2].to_string());
                        multiline = Some("deprecated");
                    }
                    "rename" => method.rename_to = Some(caps[2].to_string()),
                    "action" => method.action = Some(caps[2].to_string()),
                    // Ignore for now, not used at the moment
                    "agent_version" | "agent_requirements" => (),
                    p if p.starts_with("parameter") => (),
                    _ => debug!("Unknown tag {}", tag),
                }
                continue;
            }

            let multiline_re = regex!(r"^\s*# ?(.*)$");
            if let Some(multi) = multiline {
                if let Some(caps) = multiline_re.captures(line) {
                    match multi {
                        "description" => {
                            method.description.push('\n');
                            method.description.push_str(&caps[1]);
                        }
                        "documentation" => {
                            method.documentation.push('\n');
                            method.documentation.push_str(&caps[1]);
                        }
                        "deprecated" => {
                            let deprecated = method.deprecated.as_mut().unwrap();
                            deprecated.push('\n');
                            deprecated.push_str(&caps[1]);
                        }
                        _ => unreachable!(),
                    }
                } else {
                    // End of multiline field
                    multiline = None;
                }
                continue;
            }

            // Bundle signature
            let bundle_re = regex!(r"[^#]*bundle\s+agent\s+(\w+)\s*(\(([^)]*)\))?\s*\{?\s*$");
            if let Some(caps) = bundle_re.captures(line) {
                method.method_name = (&caps[1]).to_string();
                method.method_args = match &caps.get(3) {
                    Some(args) => args
                        .as_str()
                        .split(',')
                        .map(|s| s.trim().to_string())
                        .collect(),
                    None => vec![],
                };
                // We're done with parsing, let's stop now
                break;
            }
        }
        method.build()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn it_checks_constraints() {
        let constraints = Constraints::default();
        assert!(constraints.is_valid("").is_err());
        assert!(constraints.is_valid("    ").is_err());
        assert!(constraints.is_valid("42").is_ok());

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
