// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS

use crate::testlib::method_to_test::{
    MethodStatus, MethodToTest, ResultCondition, ResultConditionSuffix,
};
use rudder_commons::report::Report;
use tracing::debug;

#[derive(Debug, Clone)]
pub struct ExecutionResult {
    pub directive_id: String,
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
        let mut unexpected_conditions = ResultCondition::with_suffixes(
            &method_call.get_result_condition_prefix(),
            &ResultConditionSuffix::ALL,
        );
        unexpected_conditions.retain(|x| !expected_conditions.contains(x));
        for c in expected_conditions.clone() {
            assert!(
                self.conditions.contains(&c.to_string()),
                "Could not find the expected result condition '{c}'"
            );
            debug!("Found expected result condition '{c}'");
        }
        for c in unexpected_conditions.clone() {
            assert!(
                !self.conditions.contains(&c.to_string()),
                "Found unexpected result condition '{c}'",
            );
            debug!("Unexpected result condition '{c}' is absent, as expected");
        }
    }

    // When using the log v4, an incremental index is added to each method call, making
    // the exact result condition difficult to compute, using patterns is easier
    pub fn assert_log_v4_result_conditions(
        &self,
        method_call: &MethodToTest,
        expected_status: MethodStatus,
    ) {
        let result_id = format!("{}-{}", self.directive_id, method_call.id);
        let expected_conditions = method_call.log_v4_result_conditions(result_id, expected_status);
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

#[cfg(test)]
mod tests {
    use super::ExecutionResult;
    use crate::testlib::method_to_test::{
        MethodStatus, MethodToTest, ResultCondition, ResultConditionSuffix, method,
    };

    const PREFIX: &str = "file_absent__tmp_x";

    #[test]
    fn it_accepts_exactly_the_expected_conditions() {
        let m = method("file_absent", &["/tmp/x"]);
        let conditions = vec![
            "file_absent__tmp_x_ok",
            "file_absent__tmp_x_kept",
            "file_absent__tmp_x_not_repaired",
            "file_absent__tmp_x_reached",
        ]
        .iter()
        .map(|x| x.to_string())
        .collect();
        let r = ExecutionResult {
            directive_id: "directive".to_string(),
            reports: vec![],
            variables: serde_json::Value::Null,
            conditions,
        };
        r.assert_legacy_result_conditions(&m, vec![MethodStatus::Success]);
    }

    #[test]
    fn it_accepts_combined_statuses() {
        let m = method("file_absent", &["/tmp/x"]);
        let conditions = vec![
            "file_absent__tmp_x_ok",
            "file_absent__tmp_x_kept",
            "file_absent__tmp_x_not_repaired",
            "file_absent__tmp_x_not_kept",
            "file_absent__tmp_x_repaired",
            "file_absent__tmp_x_reached",
        ]
        .iter()
        .map(|x| x.to_string())
        .collect();
        let r = ExecutionResult {
            directive_id: "directive".to_string(),
            reports: vec![],
            variables: serde_json::Value::Null,
            conditions,
        };
        r.assert_legacy_result_conditions(&m, vec![MethodStatus::Success, MethodStatus::Repaired]);
    }

    #[test]
    fn it_ignores_conditions_that_are_not_result_conditions() {
        let m = method("file_absent", &["/tmp/x"]);
        let conditions = vec![
            "file_absent__tmp_x_ok",
            "file_absent__tmp_x_kept",
            "file_absent__tmp_x_not_repaired",
            "file_absent__tmp_x_reached",
            "file_absent__tmp_x_reachededededed",
            "file_absent__tmp_y_error",
            "file_absent__tmp_x_and_error",
        ]
        .iter()
        .map(|x| x.to_string())
        .collect();
        let r = ExecutionResult {
            directive_id: "directive".to_string(),
            reports: vec![],
            variables: serde_json::Value::Null,
            conditions,
        };
        r.assert_legacy_result_conditions(&m, vec![MethodStatus::Success]);
    }

    #[test]
    #[should_panic(
        expected = "Could not find the expected result condition 'file_absent__tmp_x_reached'"
    )]
    fn it_fails_when_an_expected_condition_is_missing() {
        let m = method("file_absent", &["/tmp/x"]);
        let conditions = vec![
            "file_absent__tmp_x_ok",
            "file_absent__tmp_x_kept",
            "file_absent__tmp_x_not_repaired",
        ]
        .iter()
        .map(|x| x.to_string())
        .collect();
        let r = ExecutionResult {
            directive_id: "directive".to_string(),
            reports: vec![],
            variables: serde_json::Value::Null,
            conditions,
        };
        r.assert_legacy_result_conditions(&m, vec![MethodStatus::Success]);
    }

    #[test]
    #[should_panic(expected = "Found unexpected result condition 'file_absent__tmp_x_error")]
    fn it_fails_when_an_unbexpected_condition_is_found() {
        let m = method("file_absent", &["/tmp/x"]);
        let conditions = vec![
            "file_absent__tmp_x_ok",
            "file_absent__tmp_x_kept",
            "file_absent__tmp_x_not_repaired",
            "file_absent__tmp_x_reached",
            "file_absent__tmp_x_error",
        ]
        .iter()
        .map(|x| x.to_string())
        .collect();
        let r = ExecutionResult {
            directive_id: "directive".to_string(),
            reports: vec![],
            variables: serde_json::Value::Null,
            conditions,
        };
        r.assert_legacy_result_conditions(&m, vec![MethodStatus::Success]);
    }
}
