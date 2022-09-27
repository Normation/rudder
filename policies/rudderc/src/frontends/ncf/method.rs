// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

//! Models of the classic "ncf" methods of Rudder
//!
//! Uses a method (function-like) based model.

// Parseur de metadata à partir d'un .cf
// C'est quoi l'interface d'une generic method ?
// Faire la conversion vers une resource

// transformer en "native resources" qui sont aussi définissables
// à la main

use std::{collections::HashMap, path::PathBuf, str::FromStr};

use anyhow::{bail, Error, Result};
use log::debug;
use serde::{Deserialize, Serialize};

use rudder_commons::{Constraint, Constraints, ParameterType, Target};

pub type MethodName = String;
pub type AttributeName = String;

/// metadata about a "ncf" CFEngine/DSC method
///
/// Leaf yaml implemented by ncf
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize, Default)]
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

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Parameter {
    pub description: String,
    pub constraints: Constraints,
    pub renamed_to: Option<AttributeName>,
    pub p_type: ParameterType,
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
                method.method_name = (caps[1]).to_string();
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
