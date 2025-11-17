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
        #[cfg(feature = "test-unix")]
        MethodStatus::Error => vec![
            "not_kept",
            "not_ok",
            "not_repaired",
            "failed", //legacy
            "error",
            "reached",
        ],
        #[cfg(not(feature = "test-unix"))]
        MethodStatus::Error => vec!["not_kept", "not_ok", "not_repaired", "error", "reached"],
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

    pub fn log_v4_result_conditions(&self, result_id: String, status: MethodStatus) -> Vec<Regex> {
        get_result_condition_suffixes(status)
            .into_iter()
            .map(|s| Regex::new(&format!("^{}_{}$", cfengine_canonify(&result_id), s)).unwrap())
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
}
pub fn method(method_name: &str, args: &[&str]) -> MethodToTest {
    let lib = get_lib();
    let method_info = lib
        .get(method_name)
        .unwrap_or_else(|| panic!("Method info not found for: {method_name}"));
    assert_eq!(
        method_info.parameter.len(),
        args.len(),
        "Parameter count mismatch for method '{}'\nExpected parameters:\n  [{}]\nFound:\n  [{}]",
        method_name,
        method_info
            .parameter
            .iter()
            .map(|p| p.name.clone())
            .join(", "),
        args.iter().join(", ")
    );
    let params = method_info
        .clone()
        .parameter
        .into_iter()
        .map(|p| p.name.clone())
        .zip(args.iter().map(|s| s.to_string()))
        .collect::<Vec<(String, String)>>()
        .into_iter()
        .collect::<HashMap<String, String>>();

    MethodToTest {
        name: method_name.to_string(),
        params,
        method_info,
        ..MethodToTest::new()
    }
}
