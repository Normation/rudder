// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use crate::{
    command::compile,
    error::*,
    io,
    ir::{ir1::IR1, resource::ResourceDef, resource::StateDef},
    parser::{Token, PAST},
};
use colored::Colorize;
use lazy_static::lazy_static;
use regex::Regex;
use std::path::Path;
use std::str;
use toml::Value as TomlValue;
use typed_arena::Arena;
use walkdir::WalkDir;

pub struct LanguageLib<'src>(IR1<'src>);

impl<'src> core::ops::Deref for LanguageLib<'src> {
    type Target = IR1<'src>;

    fn deref(self: &Self) -> &Self::Target {
        &self.0
    }
}

impl<'src> LanguageLib<'src> {
    /// Parse all `.rd` files recursively to allow future layout changes.
    /// parse the whole library and returns the IR
    pub fn new(stdlib_dir: &'src Path, sources: &'src Arena<String>) -> Result<Self> {
        let past = Self::parse(stdlib_dir, sources)?;
        Ok(Self(IR1::from_past(past)?))
    }

    /// parse the whole library and pushes it into the input PAST
    pub fn past(stdlib_dir: &'src Path, sources: &'src Arena<String>) -> Result<PAST<'src>> {
        let past = Self::parse(stdlib_dir, sources)?;
        Ok(past)
    }

    fn parse(stdlib_dir: &'src Path, sources: &'src Arena<String>) -> Result<PAST<'src>> {
        fn is_rd_file(file: &Path) -> bool {
            file.extension().map(|e| e == "rd").unwrap_or(false)
        }

        info!(
            "|- {} {}",
            "Parsing".bright_green(),
            "standard library".bright_yellow()
        );

        let mut past = PAST::new();
        let walker = WalkDir::new(stdlib_dir)
            .into_iter()
            .filter(|r| r.as_ref().map(|e| is_rd_file(e.path())).unwrap_or(false));
        for entry in walker {
            match entry {
                Ok(entry) => {
                    let (filename, content) = io::get_content(&Some(entry.into_path()))?;
                    past = compile::parse_content(past, &filename, &content, sources, true)?;
                }
                Err(err) => {
                    return Err(err!(
                        Token::new(&stdlib_dir.to_string_lossy(), ""),
                        "{}",
                        err
                    ))
                }
            }
        }
        Ok(past)
    }

    // care: if a method is actually a class_pref, does not work
    /// get from library both the resource and its relative state from a method
    /// returns Error if no pair was recognized
    pub fn method_from_str(&self, method_name: &str) -> Result<LibMethod> {
        let matched_pairs: Vec<(&Token, &Token, Option<String>)> = self
            .resources
            .iter()
            .filter_map(|(res, res_def)| {
                return Some(
                    res_def
                        .states
                        .iter()
                        .filter_map(|(state, state_def)| {
                            let owned_method_name = method_name.to_owned();
                            if owned_method_name == format!("{}_{}", res_def.name, state_def.name) {
                                return Some((res, state, None));
                            }
                            // exceptions should be dealt with here based on `method_aliases` metadata
                            if let Ok(aliases) = state_def.get_method_aliases() {
                                if aliases.contains(&owned_method_name) {
                                    return Some((res, state, Some(owned_method_name)));
                                }
                            }
                            None
                        })
                        .collect::<Vec<(&Token, &Token, Option<String>)>>(),
                );
            })
            .flatten()
            .collect();
        match matched_pairs.as_slice() {
            [] => Err(Error::new(format!(
                "Method '{}' does not exist",
                method_name
            ))),
            [(resource, state, alias)] => {
                let resource_def = self.resources.get(resource).unwrap();

                let state_def = resource_def.states.get(state).unwrap();

                Ok(LibMethod::new(resource_def, state_def, alias.to_owned()))
            }
            _ => panic!(
                "The standard library contains several matches for the following method: {}",
                method_name
            ),
        }
    }

    /// get from the lib the cfengine String representation of the operating system the cond parameter might hold
    /// returns Error if the condition is not a recognized system
    /// returns None if the condition is not an operating system
    pub fn cf_system(&self, cond: &str) -> Option<Result<String>> {
        for i in self.enum_list.enum_item_iter("system".into()) {
            match self
                .enum_list
                .enum_item_metadata("system".into(), *i)
                .expect("Enum item exists")
                .get("cfengine_name")
            {
                None => {
                    if **i == cond {
                        return Some(Ok(cond.into()));
                    }
                } // no @cfengine_name -> enum item = cfengine class
                Some(TomlValue::String(name)) => {
                    if name == cond {
                        return Some(Ok((**i).into()));
                    }
                } // simple cfengine name
                Some(TomlValue::Array(list)) => {
                    for value in list {
                        if let TomlValue::String(name) = value {
                            if name == cond {
                                return Some(Ok((**i).into()));
                            }
                        }
                    }
                } // list of cfengine names
                _ => {
                    return Some(Err(Error::new(format!(
                        "@cfengine_name must be a string or a list '{}'",
                        *i
                    ))))
                }
            }
        }
        None
    }

    pub fn cf_outcome(&self, cond: &str) -> Option<String> {
        lazy_static! {
            static ref CONDITION_RE: Regex = Regex::new(
                r"(?U)^([\w${.}]+)(?:_(not_repaired|repaired|false|true|not_ok|ok|reached|error|failed|denied|timeout|success|not_kept|kept))?$"
            ).unwrap();
        }

        // rules:
        // - classprefix_classparameters_outcomesuffix
        // - outcomesuffix is not mandatory
        // - classprefix goes in pair with class_parameters and they are not mandatory
        // - you can end up with only ${}
        if let Some(caps) = CONDITION_RE.captures(cond) {
            let method = caps.get(1).unwrap().as_str();
            let outcome = match caps.get(2) {
                Some(res) => res.as_str(),
                None => return Some(method.to_owned()),
            };
            if vec!["kept", "success"].iter().any(|x| x == &outcome) {
                return Some(format!("{} =~ kept", method));
            } else if vec!["error", "not_ok", "failed", "denied", "timeout"]
                .iter()
                .any(|x| x == &outcome)
            {
                return Some(format!("{} =~ error", method));
            } else if vec!["repaired", "ok", "reached"]
                .iter()
                .any(|x| x == &outcome)
            {
                return Some(format!("{} =~ {}", method, outcome));
            // handle condition_from exception
            } else if vec!["true", "false"].iter().any(|x| x == &outcome) {
                return match method.strip_suffix("_${report_data.canonified_directive_id}") {
                    Some(variable) => Some(format!("{} =~ {}", variable, outcome)),
                    None => Some(format!("{} =~ {}", method, outcome)),
                };
            } else if outcome == "not_kept" {
                return Some(format!("({} =~ error | {} =~ repaired)", method, method));
            }
        };
        None
    }
}

#[derive(Debug)]
pub struct LibMethod<'src> {
    pub resource: &'src ResourceDef<'src>,
    pub state: &'src StateDef<'src>,
    pub alias: Option<String>,
}
impl<'src> LibMethod<'src> {
    pub fn new(resource: &'src ResourceDef, state: &'src StateDef, alias: Option<String>) -> Self {
        Self {
            resource,
            state,
            alias,
        }
    }

    // WIP UPDATE
    pub fn class_prefix(&self) -> String {
        self.alias.clone()
            .or(self
                .state
                .metadata
                .get("class_prefix")
                .and_then(|v| v.as_str())
                .map(String::from))
            .expect(&format!(
                "Resource '{}': missing 'class_prefix' metadata and no 'method_aliases' alternative",
                self.resource.name
            ))
    }

    // safe unwrap because we generate the resourcelib ourselves, if an error occurs, panic is justified, it is a developer issue
    pub fn class_param_index(&self) -> usize {
        self.state
            .metadata
            .get("class_parameter_index")
            .expect(&format!(
                "Resource '{}': missing 'class_parameter_index' metadata",
                self.resource.name
            ))
            .as_integer()
            .expect(&format!(
                "Resource '{}': 'class_parameter_index' metadata value is not an integer",
                self.resource.name
            )) as usize
    }
}
