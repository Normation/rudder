// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use crate::{
    command::compile,
    error::*,
    io,
    ir::{ir1::IR1, resource::ResourceDef, resource::StateDef},
    parser::{Token, PAST},
    technique::outcome::ConditionOutcome,
};
use colored::Colorize;
use lazy_static::lazy_static;
use regex::Regex;
use std::{path::Path, str, str::FromStr};
use strum::IntoEnumIterator;
use toml::Value as TomlValue;
use typed_arena::Arena;
use walkdir::WalkDir;

pub struct RudderlangLib<'src>(IR1<'src>);

impl<'src> core::ops::Deref for RudderlangLib<'src> {
    type Target = IR1<'src>;

    fn deref(self: &Self) -> &Self::Target {
        &self.0
    }
}

impl<'src> RudderlangLib<'src> {
    /// Parse all `.rl` files recursively to allow future layout changes.
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
        fn is_rl_file(file: &Path) -> bool {
            file.extension().map(|e| e == "rl").unwrap_or(false)
        }

        info!(
            "|- {} {}",
            "Parsing".bright_green(),
            "standard library".bright_yellow()
        );

        let mut past = PAST::new();
        let walker = WalkDir::new(stdlib_dir)
            .into_iter()
            .filter(|r| r.as_ref().map(|e| is_rl_file(e.path())).unwrap_or(false));
        for entry in walker {
            match entry {
                Ok(entry) => {
                    let (filename, content) = io::get_content(&Some(entry.into_path()))?;
                    past = compile::parse_content(past, &filename, &content, sources)?;
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
        let matched_pairs: Vec<(&Token, &Token)> = self
            .resources
            .iter()
            .filter_map(|(res, resdef)| {
                if method_name.starts_with(resdef.name) {
                    return Some(
                        resdef
                            .states
                            .iter()
                            .filter_map(|(state, state_def)| {
                                if method_name == format!("{}_{}", resdef.name, state_def.name) {
                                    return Some((res, state));
                                }
                                None
                            })
                            .collect::<Vec<(&Token, &Token)>>(),
                    );
                }
                None
            })
            .flatten()
            .collect();
        match matched_pairs.as_slice() {
            [] => Err(Error::new(format!(
                "Method '{}' does not exist",
                method_name
            ))),
            [(resource, state)] => {
                let resource_def = self.resources.get(resource).unwrap();

                let state_def = resource_def.states.get(state).unwrap();

                Ok(LibMethod::new(resource_def, state_def))
            }
            _ => panic!(format!(
                "The standard library contains several matches for the following method: {}",
                method_name
            )),
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

    pub fn cf_outcome(&self, cond: &str) -> Option<(String, Vec<ConditionOutcome>)> {
        lazy_static! {
            static ref CONDITION_RE: Regex = Regex::new(&format!(
                r"(?U)^([\w${{.}}]+)(?:_({}))?$",
                ConditionOutcome::iter()
                    .map(|outcome| outcome.to_string())
                    .collect::<Vec<String>>()
                    .join("|")
            ))
            .unwrap();
        }

        // rules:
        // - classprefix_classparameters_outcomesuffix
        // - outcomesuffix is not mandatory
        // - classprefix goes in pair with class_parameters and they are not mandatory
        // - you can end up with only ${}
        if let Some(caps) = CONDITION_RE.captures(cond) {
            let method = caps.get(1).unwrap().as_str();
            let outcome: Vec<ConditionOutcome> = match caps.get(2) {
                Some(res) => {
                    // safe unwrap since it's directly deduced from the enum variants
                    let outcome = ConditionOutcome::from_str(res.as_str()).unwrap();
                    match outcome {
                        ConditionOutcome::Kept | ConditionOutcome::Success => {
                            vec![ConditionOutcome::Success]
                        }
                        ConditionOutcome::Error
                        | ConditionOutcome::NotOk
                        | ConditionOutcome::Failed
                        | ConditionOutcome::Denied
                        | ConditionOutcome::Timeout
                        | ConditionOutcome::NotRepaired => vec![ConditionOutcome::Error],
                        // by exception 2 possibilities
                        ConditionOutcome::NotKept => {
                            vec![ConditionOutcome::Error, ConditionOutcome::Repaired]
                        }
                        _ => vec![outcome],
                    }
                }
                None => return Some((method.to_owned(), Vec::new())),
            };
            let formatted_condition = outcome
                .iter()
                .map(|o| format!("{} =~ {}", method, o))
                .collect::<Vec<String>>()
                .join("|");
            return Some((formatted_condition, outcome));
        };
        None
    }
}

pub struct LibMethod<'src> {
    pub resource: &'src ResourceDef<'src>,
    pub state: &'src StateDef<'src>,
}
impl<'src> LibMethod<'src> {
    pub fn new(resource: &'src ResourceDef, state: &'src StateDef) -> Self {
        Self { resource, state }
    }

    pub fn class_prefix(&self) -> String {
        self.state
            .metadata
            .get("class_prefix")
            .expect(&format!(
                "Resource '{}': missing 'class_prefix' metadata",
                self.resource.name
            ))
            .as_str()
            .expect(&format!(
                "Resource '{}': 'class_prefix' metadata value is not a string",
                self.resource.name
            ))
            .to_owned()
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
