// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS

use crate::testlib::method_to_test::{MethodStatus, MethodToTest};
use log::debug;
use rudder_commons::report::Report;

#[derive(Debug, Clone)]
pub struct ExecutionResult {
    pub reports: Vec<Report>,
    pub conditions: Vec<std::string::String>,
    pub variables: serde_json::Value,
}

impl ExecutionResult {
    pub fn assert_legacy_result_conditions(
        &self,
        method_call: &MethodToTest,
        expected_status: Vec<MethodStatus>,
    ) {
        let expected_conditions = method_call.legacy_result_conditions(expected_status);
        for c in expected_conditions.clone() {
            assert!(
                self.conditions.contains(&c),
                "Could not find the expected result condition '{c}'"
            );
            debug!("Found expected result condition '{c}'");
        }
        let pattern = method_call.get_result_condition_prefix();
        let matching: Vec<String> = self
            .conditions
            .clone()
            .into_iter()
            .filter(|c| c.starts_with(&pattern) && !expected_conditions.contains(c))
            .collect();
        assert!(
            matching.is_empty(),
            "Found unexpected result conditions in the datastate:\n[\n  {}\n]",
            matching.join(",\n  ")
        );
    }

    // When using the log v4, an incremental index is added to each method call, making
    // the exact result condition difficult to compute, using patterns is easier
    pub fn assert_log_v4_result_conditions(
        &self,
        method_call: &MethodToTest,
        expected_status: MethodStatus,
    ) {
        let expected_conditions = method_call.log_v4_result_conditions(expected_status);
        for expected_pattern in expected_conditions.clone() {
            assert!(
                self.conditions.iter().any(|c| expected_pattern.is_match(c)),
                "Could not find the expected result condition '{expected_pattern}'"
            );
            debug!(
                "Found expected log v4 result condition '{}'",
                expected_pattern.as_str()
            );
        }
        // In log v4 we expected result conditions under the form <directive_id>_<method_id>_<index>_<status>
        let matching = self
            .conditions
            .clone()
            .into_iter()
            .filter(|c| c.contains(&method_call.id.to_string()))
            .collect::<Vec<String>>();
        assert!(
            matching.is_empty(),
            "Found unexpected log v4 result conditions in the datastate:\n[\n  {}\n]",
            matching.join(",\n  ")
        )
    }

    pub fn assert_conditions_are_defined(&self, conditions: Vec<String>) {
        conditions
            .iter()
            .for_each(|c| assert!(self.conditions.contains(c)))
    }

    pub fn assert_conditions_are_undefined(&self, conditions: Vec<String>) {
        conditions
            .iter()
            .for_each(|c| assert!(!self.conditions.contains(c)))
    }
}
