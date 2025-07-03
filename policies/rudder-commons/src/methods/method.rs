// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

//! Models of the classic "generic methods" of Rudder
//!
//! Use a method (function-like) based model.

use std::{path::PathBuf, str::FromStr};

use anyhow::{Error, Result, bail};
use log::debug;
use serde::{Deserialize, Serialize};

use crate::{Escaping, MethodConstraint, MethodConstraints, Target, regex_comp};

/// Supported agent for a legacy method, now replaced by `Target`
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum Agent {
    CfengineCommunity,
    Dsc,
}

impl From<Agent> for Target {
    fn from(s: Agent) -> Target {
        match s {
            Agent::Dsc => Target::Windows,
            Agent::CfengineCommunity => Target::Unix,
        }
    }
}

/// metadata about a "methods" CFEngine/DSC method
///
/// Leaf yaml implemented by methods
///
/// We use legacy names and semantics here for clarity.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct MethodInfo {
    pub name: String,
    pub bundle_name: String,
    pub bundle_args: Vec<String>,
    pub description: String,
    /// Markdown formatted documentation
    #[serde(skip_serializing_if = "Option::is_none")]
    pub documentation: Option<String>,
    pub agent_support: Vec<Agent>,
    pub class_prefix: String,
    pub class_parameter: String,
    pub class_parameter_id: usize,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub source: Option<PathBuf>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub deprecated: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub rename: Option<String>,
    /// There can be a message about the cause
    #[serde(skip_serializing_if = "Option::is_none")]
    pub action: Option<String>,
    pub parameter: Vec<Parameter>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    #[serde(default)]
    pub parameter_rename: Vec<ParameterRename>,
}

impl MethodInfo {
    // There is no specific builder type but let's treat it as is
    //
    // Check validity and clean thing up (trim, etc.)
    fn build(mut self) -> Result<Self> {
        self.name = self.name.trim().to_string();
        self.description = self.description.trim().to_string();
        if let Some(d) = self.documentation {
            self.documentation = Some(d.trim().to_string())
        }
        if let Some(deprecated) = self.deprecated.as_mut() {
            *deprecated = deprecated.trim().to_string();
        }

        if self.name.is_empty() {
            bail!("Empty method name");
        }
        if self.description.is_empty() {
            bail!("Empty method description");
        }

        self.class_parameter_id = match self
            .bundle_args
            .iter()
            .position(|a| a == &self.class_parameter)
        {
            // Starts at 1 for some reason
            Some(i) => i + 1,
            None => bail!("Unknown class parameter"),
        };

        if self.agent_support.is_empty() {
            // Assume CFEngine support for user-written methods
            self.agent_support.push(Agent::CfengineCommunity)
        }

        Ok(self)
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ParameterRename {
    old: String,
    new: String,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Parameter {
    pub name: String,
    pub description: String,
    pub constraints: MethodConstraints,
    #[serde(rename = "type")]
    pub escaping: Escaping,
}

impl Default for Parameter {
    fn default() -> Self {
        Self {
            name: "".to_string(),
            description: "".to_string(),
            constraints: MethodConstraints::default(),
            escaping: Escaping::String,
        }
    }
}

/// Parser
impl FromStr for MethodInfo {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        let mut method = MethodInfo::default();
        // Kept the same logic and regexes as methods.py for two big reasons:
        //
        // * compatibility
        // * laziness

        // Store current multiline tag
        let mut multiline = None;

        for line in s.lines() {
            // Parameter tags (with a parameter sub key)
            let attributes_tag_re =
                regex_comp!(r"^\s*#\s*@(parameter\w*)\s*(([a-zA-Z0-9_]+)?\s+(.*?)|.*?)\s*$");
            if let Some(caps) = attributes_tag_re.captures(line) {
                multiline = None;
                let tag = &caps[1];
                let parameter_name = &caps[3];

                // Short circuit as it is a special case
                if tag == "parameter_rename" {
                    method.parameter_rename.push(ParameterRename {
                        new: caps[4].to_string(),
                        old: parameter_name.to_owned(),
                    });
                    continue;
                }

                // Insert parameter if it is not already there
                if !method.parameter.iter().any(|p| p.name == parameter_name) {
                    let new = Parameter {
                        name: parameter_name.to_owned(),
                        ..Default::default()
                    };
                    method.parameter.push(new);
                }
                // Get mutable reference
                let parameter = method
                    .parameter
                    .iter_mut()
                    .find(|p| p.name == parameter_name)
                    .unwrap();

                match tag {
                    "parameter" => parameter.description = caps[4].to_string(),
                    "parameter_constraint" => {
                        // Stored as ~ JSON
                        let constraint: MethodConstraint = serde_json::from_str(&format!(
                            "{{ {} }}",
                            caps[4].replace('\\', "\\\\")
                        ))?;
                        parameter.constraints.update(constraint)?;
                    }
                    "parameter_type" => parameter.escaping = caps[4].parse()?,
                    _ => debug!("Unknown tag {tag}"),
                }
                continue;
            }

            // Other tags, allow multiline
            let tag_re = regex_comp!(r"^\s*#\s*@(\w+)\s*(([a-zA-Z0-9_]+)?\s+(.*?)|.*?)\s*$");
            if let Some(caps) = tag_re.captures(line) {
                multiline = None;
                let tag = &caps[1];
                match tag {
                    "name" => method.name = caps[2].to_string(),
                    "description" => {
                        method.description = caps[2].to_string();
                        multiline = Some("description");
                    }
                    "documentation" => {
                        method.documentation = Some(caps[2].to_string());
                        multiline = Some("documentation");
                    }
                    "class_prefix" => method.class_prefix = caps[2].to_string(),
                    "class_parameter" => method.class_parameter = caps[2].to_string(),
                    "deprecated" => {
                        method.deprecated = Some(caps[2].to_string());
                        multiline = Some("deprecated");
                    }
                    "rename" => method.rename = Some(caps[2].to_string()),
                    "action" => method.action = Some(caps[2].to_string()),
                    // Ignore for now, not used at the moment
                    "agent_version" | "agent_requirements" => (),
                    "agent_support" => {
                        if caps[2].contains("\"cfengine-community\"") {
                            method.agent_support.push(Agent::CfengineCommunity)
                        }
                        if caps[2].contains("\"dsc\"") {
                            method.agent_support.push(Agent::Dsc)
                        }
                    }
                    p if p.starts_with("parameter") => (),
                    _ => debug!("Unknown tag {tag}"),
                }
                continue;
            }

            let multiline_re = regex_comp!(r"^\s*# ?(.*)$");
            if let Some(multi) = multiline {
                if let Some(caps) = multiline_re.captures(line) {
                    match multi {
                        "description" => {
                            method.description.push('\n');
                            method.description.push_str(&caps[1]);
                        }
                        "documentation" => {
                            if let Some(ref mut d) = method.documentation {
                                d.push('\n');
                                d.push_str(&caps[1]);
                            }
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

            let bundle_re = regex_comp!(r"[^#]*bundle\s+agent.*$");
            if bundle_re.captures(line).is_some() {
                // We're done with metadata parsing, let's stop now
                break;
            }
        }

        // Bundle signature
        let bundle_re = regex_comp!(r"[^#{]*bundle\s+agent\s+(\w+)\s*(\(([^)]*)\))?\s*\{?\s*");
        // Select the first bundle, which is by convention the main one
        if let Some(caps) = bundle_re.captures(s) {
            method.bundle_name = (caps[1]).to_string();
            method.bundle_args = match &caps.get(3) {
                Some(args) => args
                    .as_str()
                    .split(',')
                    .map(|s| s.trim().to_string())
                    .collect(),
                None => vec![],
            };
        }

        method.build()
    }
}
