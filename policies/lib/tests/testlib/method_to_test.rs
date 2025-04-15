// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS

use crate::integration::get_lib;
use anyhow::Context;
use itertools::Itertools;
use regex::Regex;
use rudder_commons::PolicyMode;
use rudder_commons::methods::method::MethodInfo;
use rudderc::backends::unix::cfengine::cfengine_canonify;
use rudderc::ir::technique::{Id, ItemKind, Method};
use std::collections::HashMap;

pub enum MethodStatus {
    Success,
    Repaired,
    Error,
    NA,
}

pub fn get_result_condition_suffixes(status: MethodStatus) -> Vec<String> {
    let v = match status {
        MethodStatus::Success => vec!["ok", "kept", "not_repaired", "reached"],
        MethodStatus::Repaired => vec!["ok", "repaired", "not_kept", "reached"],
        MethodStatus::Error => vec![
            "not_kept",
            "not_ok",
            "not_repaired",
            "failed",
            "error",
            "reached",
        ],
        MethodStatus::NA => vec!["noop"],
    };
    v.iter().map(|s| s.to_string()).collect()
}
#[derive(Clone)]
pub struct MethodToTest {
    pub id: Id,
    pub name: String,
    pub params: HashMap<String, String>,
    pub method_info: &'static MethodInfo,
    pub policy_mode: PolicyMode,
}

impl Default for MethodToTest {
    fn default() -> Self {
        Self::new()
    }
}

impl MethodToTest {
    pub fn new() -> MethodToTest {
        MethodToTest {
            id: Default::default(),
            name: "file_absent".to_string(),
            params: HashMap::from([("path".to_string(), "/tmp/default_target.txt".to_string())]),
            method_info: get_lib()
                .get("file_absent")
                .context("Looking for the method metadata from the parsed library")
                .unwrap(),
            policy_mode: Default::default(),
        }
    }

    pub fn audit(self) -> MethodToTest {
        MethodToTest {
            policy_mode: PolicyMode::Audit,
            ..self
        }
    }

    pub fn enforce(self) -> MethodToTest {
        MethodToTest {
            policy_mode: PolicyMode::Enforce,
            ..self
        }
    }
    pub fn to_item_kind(&self) -> ItemKind {
        ItemKind::Method(Method {
            name: format!("Testing method {}", self.id),
            id: self.id.clone(),
            policy_mode_override: Some(self.policy_mode),
            method: self.name.clone(),
            params: self.params.clone(),
            info: Some(self.method_info),
            description: Default::default(),
            documentation: Default::default(),
            tags: Default::default(),
            reporting: Default::default(),
            condition: Default::default(),
            resolved_foreach_state: Default::default(),
        })
    }
    pub fn get_result_condition_prefix(&self) -> String {
        cfengine_canonify(&format!(
            "{}_{}_",
            self.method_info.class_prefix,
            self.params.get(&self.method_info.class_parameter).unwrap()
        ))
    }

    pub fn log_v4_result_conditions(&self, status: MethodStatus) -> Vec<Regex> {
        get_result_condition_suffixes(status)
            .into_iter()
            .map(|s| {
                Regex::new(&format!(
                    "^{}_{}_{}$",
                    cfengine_canonify(&self.id.to_string()),
                    r"\d+",
                    s
                ))
                .unwrap()
            })
            .collect()
    }

    // As legacy result condition can overlap between method calls, we have to handle
    // combinations of expected statuses
    pub fn legacy_result_conditions(&self, statuses: Vec<MethodStatus>) -> Vec<String> {
        let mut expected_suffixes: Vec<String> = Vec::new();
        statuses.into_iter().for_each(|status| {
            expected_suffixes.extend(get_result_condition_suffixes(status));
        });
        expected_suffixes
            .into_iter()
            .unique()
            .map(|s| {
                cfengine_canonify(&format!(
                    "{}_{}_{}",
                    &self.method_info.class_prefix,
                    self.params.get(&self.method_info.class_parameter).unwrap(),
                    s
                ))
            })
            .collect::<Vec<String>>()
    }

    // Below are the generic method constructors
    pub fn command_execution(command: String) -> MethodToTest {
        MethodToTest {
            name: "command_execution".to_string(),
            params: HashMap::from([("command".to_string(), command)]),
            method_info: get_lib()
                .get("command_execution")
                .context("Looking for the method metadata from the parsed library")
                .unwrap(),
            ..Self::new()
        }
    }
    pub fn file_absent(path: String) -> MethodToTest {
        MethodToTest {
            name: "file_absent".to_string(),
            params: HashMap::from([("path".to_string(), path)]),
            method_info: get_lib()
                .get("file_absent")
                .context("Looking for the method metadata from the parsed library")
                .unwrap(),
            ..Self::new()
        }
    }
    pub fn file_check_exists(path: String) -> MethodToTest {
        MethodToTest {
            name: "file_check_exists".to_string(),
            params: HashMap::from([("path".to_string(), path)]),
            method_info: get_lib()
                .get("file_check_exists")
                .context("Looking for the method metadata from the parsed library")
                .unwrap(),
            ..Self::new()
        }
    }
    pub fn condition_from_expression(condition: String, expression: String) -> MethodToTest {
        MethodToTest {
            name: "condition_from_expression".to_string(),
            params: HashMap::from([
                ("condition".to_string(), condition),
                ("expression".to_string(), expression),
            ]),
            method_info: get_lib()
                .get("condition_from_expression")
                .context("Looking for the method metadata from the parsed library")
                .unwrap(),
            ..Self::new()
        }
    }
    pub fn condition_from_variable_match(
        condition: String,
        variable_name: String,
        expected_match: String,
    ) -> MethodToTest {
        MethodToTest {
            name: "condition_from_variable_match".to_string(),
            params: HashMap::from([
                ("condition".to_string(), condition),
                ("variable_name".to_string(), variable_name),
                ("expected_match".to_string(), expected_match),
            ]),
            method_info: get_lib()
                .get("condition_from_variable_match")
                .context("Looking for the method metadata from the parsed library")
                .unwrap(),
            ..Self::new()
        }
    }
    pub fn condition_from_variable_existence(
        condition: String,
        variable_name: String,
    ) -> MethodToTest {
        MethodToTest {
            name: "condition_from_variable_existence".to_string(),
            params: HashMap::from([
                ("condition".to_string(), condition),
                ("variable_name".to_string(), variable_name),
            ]),
            method_info: get_lib()
                .get("condition_from_variable_existence")
                .context("Looking for the method metadata from the parsed library")
                .unwrap(),
            ..Self::new()
        }
    }
    pub fn variable_string(prefix: String, name: String, value: String) -> MethodToTest {
        MethodToTest {
            name: "variable_string".to_string(),
            params: HashMap::from([
                ("prefix".to_string(), prefix),
                ("name".to_string(), name),
                ("value".to_string(), value),
            ]),
            method_info: get_lib()
                .get("variable_string")
                .context("Looking for the method metadata from the parsed library")
                .unwrap(),
            ..Self::new()
        }
    }
    pub fn variable_dict(prefix: String, name: String, value: String) -> MethodToTest {
        MethodToTest {
            name: "variable_dict".to_string(),
            params: HashMap::from([
                ("prefix".to_string(), prefix),
                ("name".to_string(), name),
                ("value".to_string(), value),
            ]),
            method_info: get_lib()
                .get("variable_dict")
                .context("Looking for the method metadata from the parsed library")
                .unwrap(),
            ..Self::new()
        }
    }

    pub fn file_content(path: String, lines: String, enforce: String) -> MethodToTest {
        MethodToTest {
            name: "file_content".to_string(),
            params: HashMap::from([
                ("path".to_string(), path),
                ("lines".to_string(), lines),
                ("enforce".to_string(), enforce),
            ]),
            method_info: get_lib()
                .get("file_content")
                .context("Looking for the method metadata from the parsed library")
                .unwrap(),
            ..Self::new()
        }
    }
}
