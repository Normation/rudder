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
use std::fmt;

pub enum MethodStatus {
    Success,
    Repaired,
    Error,
    NA,
}

#[derive(Clone, Debug, PartialEq, Eq, Hash, Ord, PartialOrd)]
pub enum ResultConditionSuffix {
    Ok,
    NotOk,
    Kept,
    NotKept,
    Repaired,
    NotRepaired,
    Reached,
    Error,
    Noop,
    Failed,
}

impl ResultConditionSuffix {
    pub const ALL: [ResultConditionSuffix; 10] = [
        ResultConditionSuffix::Ok,
        ResultConditionSuffix::NotOk,
        ResultConditionSuffix::Kept,
        ResultConditionSuffix::NotKept,
        ResultConditionSuffix::Repaired,
        ResultConditionSuffix::NotRepaired,
        ResultConditionSuffix::Reached,
        ResultConditionSuffix::Error,
        ResultConditionSuffix::Noop,
        ResultConditionSuffix::Failed,
    ];

    pub const SUCCESS: [ResultConditionSuffix; 4] = [
        ResultConditionSuffix::Ok,
        ResultConditionSuffix::Kept,
        ResultConditionSuffix::NotRepaired,
        ResultConditionSuffix::Reached,
    ];

    pub const REPAIRED: [ResultConditionSuffix; 4] = [
        ResultConditionSuffix::Ok,
        ResultConditionSuffix::NotKept,
        ResultConditionSuffix::Repaired,
        ResultConditionSuffix::Reached,
    ];

    #[cfg(feature = "test-unix")]
    pub const ERROR: [ResultConditionSuffix; 6] = [
        ResultConditionSuffix::NotOk,
        ResultConditionSuffix::NotKept,
        ResultConditionSuffix::NotRepaired,
        ResultConditionSuffix::Reached,
        ResultConditionSuffix::Error,
        ResultConditionSuffix::Failed,
    ];
    #[cfg(not(feature = "test-unix"))]
    pub const ERROR: [ResultConditionSuffix; 5] = [
        ResultConditionSuffix::NotOk,
        ResultConditionSuffix::NotKept,
        ResultConditionSuffix::NotRepaired,
        ResultConditionSuffix::Reached,
        ResultConditionSuffix::Error,
    ];
    pub const NA: [ResultConditionSuffix; 1] = [ResultConditionSuffix::Noop];

    pub fn as_str(&self) -> &'static str {
        match self {
            ResultConditionSuffix::Ok => "ok",
            ResultConditionSuffix::NotOk => "not_ok",
            ResultConditionSuffix::Kept => "kept",
            ResultConditionSuffix::NotKept => "not_kept",
            ResultConditionSuffix::Repaired => "repaired",
            ResultConditionSuffix::NotRepaired => "not_repaired",
            ResultConditionSuffix::Reached => "reached",
            ResultConditionSuffix::Error => "error",
            ResultConditionSuffix::Noop => "noop",
            ResultConditionSuffix::Failed => "failed",
        }
    }

    pub fn for_status(status: MethodStatus) -> Vec<ResultConditionSuffix> {
        match status {
            MethodStatus::Success => ResultConditionSuffix::SUCCESS.to_vec(),
            MethodStatus::Repaired => ResultConditionSuffix::REPAIRED.to_vec(),
            MethodStatus::Error => ResultConditionSuffix::ERROR.to_vec(),
            MethodStatus::NA => ResultConditionSuffix::NA.to_vec(),
        }
    }
}

impl fmt::Display for ResultConditionSuffix {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.as_str())
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Hash, Ord, PartialOrd)]
pub struct ResultCondition {
    pub prefix: String,
    pub suffix: ResultConditionSuffix,
}

impl ResultCondition {
    pub fn with_suffixes(prefix: &str, suffixes: &[ResultConditionSuffix]) -> Vec<ResultCondition> {
        suffixes
            .iter()
            .map(|s| ResultCondition {
                prefix: prefix.to_string(),
                suffix: s.clone(),
            })
            .collect()
    }

    pub fn from_status(prefix: &str, status: MethodStatus) -> Vec<ResultCondition> {
        let s: &[ResultConditionSuffix] = match status {
            MethodStatus::Success => &ResultConditionSuffix::SUCCESS,
            MethodStatus::Repaired => &ResultConditionSuffix::REPAIRED,
            MethodStatus::Error => &ResultConditionSuffix::ERROR,
            MethodStatus::NA => &ResultConditionSuffix::NA,
        };
        ResultCondition::with_suffixes(&prefix, s)
    }

    pub fn from_statuses(prefix: &str, statuses: Vec<MethodStatus>) -> Vec<ResultCondition> {
        let mut result: Vec<ResultCondition> = statuses
            .into_iter()
            .flat_map(|s| ResultCondition::from_status(prefix, s))
            .collect();
        result.sort();
        result.dedup();
        result
    }
}

impl fmt::Display for ResultCondition {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}_{}", self.prefix, self.suffix)
    }
}

pub fn get_result_condition_suffixes(status: MethodStatus) -> Vec<String> {
    ResultConditionSuffix::for_status(status)
        .iter()
        .map(|s| s.as_str().to_string())
        .collect()
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
            "{}_{}",
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
    pub fn legacy_result_conditions(&self, statuses: Vec<MethodStatus>) -> Vec<ResultCondition> {
        let prefix = self.get_result_condition_prefix();
        ResultCondition::from_statuses(&prefix, statuses)
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
