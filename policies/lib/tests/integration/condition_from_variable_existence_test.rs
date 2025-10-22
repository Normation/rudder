// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS

use crate::integration::{end_test, get_lib_path, init_test};
use crate::testlib::given::Given;
use crate::testlib::method_test_suite::MethodTestSuite;
use crate::testlib::method_to_test::{MethodStatus, method};

#[test]
fn it_should_generate_the_true_conditions_if_needed() {
    let workdir = init_test();
    let variable_def =
        &method("variable_string", &["my_prefix", "my_name", "hello world"]).enforce();
    let tested_method = &method(
        "condition_from_variable_existence",
        &["plouf", "my_prefix.my_name"],
    )
    .enforce();
    let r = MethodTestSuite::new()
        .given(Given::method_call(variable_def))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Success]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Success);
    r.assert_conditions_are_defined(vec!["plouf_true".to_string()]);
    r.assert_conditions_are_undefined(vec!["plouf_false".to_string()]);
    end_test(workdir);
}
#[test]
fn it_should_generate_the_false_conditions_if_needed() {
    let workdir = init_test();
    let variable_def =
        &method("variable_string", &["my_prefix", "my_name", "hello world"]).enforce();
    let tested_method = &method(
        "condition_from_variable_existence",
        &["plouf", "my_prefix.my_wrong_name"],
    )
    .enforce();
    let r = MethodTestSuite::new()
        .given(Given::method_call(variable_def))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Success]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Success);
    r.assert_conditions_are_defined(vec!["plouf_false".to_string()]);
    r.assert_conditions_are_undefined(vec!["plouf_true".to_string()]);
    end_test(workdir);
}
#[test]
fn it_should_work_on_dict_variable() {
    let workdir = init_test();
    let variable_def = &method(
        "variable_dict",
        &["my_prefix", "my_name", r#"{"key": "hello world"}"#],
    )
    .enforce();
    let tested_method = &method(
        "condition_from_variable_existence",
        &["plouf", "my_prefix.my_name"],
    )
    .enforce();
    let r = MethodTestSuite::new()
        .given(Given::method_call(variable_def))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Success]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Success);
    r.assert_conditions_are_defined(vec!["plouf_true".to_string()]);
    r.assert_conditions_are_undefined(vec!["plouf_false".to_string()]);
    end_test(workdir);
}
