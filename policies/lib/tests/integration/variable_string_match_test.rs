// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

use crate::integration::{end_test, get_lib_path, init_test};
use crate::testlib::given::Given;
use crate::testlib::method_test_suite::MethodTestSuite;
use crate::testlib::method_to_test::{MethodStatus, method};

#[test]
fn it_should_report_an_error_when_the_variable_does_not_exist() {
    let workdir = init_test();
    let tested_method =
        &method("variable_string_match", &["my_prefix.my_variable", ".*"]).enforce();
    let r = MethodTestSuite::new()
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Error]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Error);
    end_test(workdir);
}

#[test]
fn it_should_report_an_error_when_the_variable_does_not_matches_the_pattern() {
    let workdir = init_test();
    let variable_def = &method(
        "variable_string",
        &["my_prefix", "my_variable", "hello world"],
    )
    .enforce();
    let tested_method = &method(
        "variable_string_match",
        &["my_prefix.my_variable", ".*hello.*!"],
    )
    .enforce();
    let r = MethodTestSuite::new()
        .given(Given::method_call(variable_def))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Error]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Error);
    assert_eq!(
        r.variables["my_prefix"]["my_variable"].as_str().unwrap(),
        "hello world",
        "The variable my_prefix.my_variable was not defined by the test setup",
    );
    end_test(workdir);
}
#[test]
fn it_should_report_a_success_when_the_variable_matches_the_pattern() {
    let workdir = init_test();
    let variable_def = &method(
        "variable_string",
        &["my_prefix", "my_variable", "hello world"],
    )
    .enforce();
    let tested_method = &method(
        "variable_string_match",
        &["my_prefix.my_variable", ".*hello.*"],
    )
    .enforce();
    let r = MethodTestSuite::new()
        .given(Given::method_call(variable_def))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Success]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Success);
    assert_eq!(
        r.variables["my_prefix"]["my_variable"].as_str().unwrap(),
        "hello world",
        "The variable my_prefix.my_variable was not defined by the test setup",
    );
    end_test(workdir);
}
#[test]
fn it_should_report_an_error_if_the_variable_is_not_a_string() {
    let workdir = init_test();
    let variable_def = &method(
        "variable_dict",
        &["my_prefix", "my_variable", r#"{ "foo": "bar" }"#],
    )
    .enforce();
    let tested_method =
        &method("variable_string_match", &["my_prefix.my_variable", ".*"]).enforce();
    let r = MethodTestSuite::new()
        .given(Given::method_call(variable_def))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Error]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Error);
    //end_test(workdir);
}
