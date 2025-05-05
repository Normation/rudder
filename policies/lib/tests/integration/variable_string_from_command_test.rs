// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS

use crate::integration::{end_test, get_lib_path, init_test};
use crate::testlib::method_test_suite::MethodTestSuite;
use crate::testlib::method_to_test::{MethodStatus, method};

#[test]
fn it_should_define_the_variable() {
    let workdir = init_test();
    let tested_method = &method(
        "variable_string_from_command",
        &["my_prefix", "my_variable", "echo 'foo'"],
    )
    .enforce();
    let r = MethodTestSuite::new()
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Success]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Success);
    assert!(
        r.variables["my_prefix"]["my_variable"].is_string(),
        "The variable my_prefix.my_variable was not defined by the method execution",
    );
    assert_eq!(
        r.variables["my_prefix"]["my_variable"].as_str().unwrap(),
        "foo",
        "The variable my_prefix.my_variable was not defined by the method execution",
    );
    end_test(workdir);
}
#[test]
fn it_should_define_the_variable_in_audit() {
    let workdir = init_test();
    let tested_method = &method(
        "variable_string_from_command",
        &["my_prefix", "my_variable", "echo 'foo'"],
    )
    .audit();
    let r = MethodTestSuite::new()
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Success]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Success);
    assert!(
        r.variables["my_prefix"]["my_variable"].is_string(),
        "The variable my_prefix.my_variable was not defined by the method execution",
    );
    assert_eq!(
        r.variables["my_prefix"]["my_variable"].as_str().unwrap(),
        "foo",
        "The variable my_prefix.my_variable was not defined by the method execution",
    );
    end_test(workdir);
}
#[test]
fn it_should_not_define_the_variable_if_the_command_fails() {
    let workdir = init_test();
    let tested_method = &method(
        "variable_string_from_command",
        &["my_prefix", "my_variable", "echo 'foo' && /bin/false"],
    )
    .audit();
    let r = MethodTestSuite::new()
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Error]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Error);
    assert!(
        r.variables.get("my_prefix").is_none(),
        "The variable my_prefix.my_variable was defined by the method execution",
    );
    end_test(workdir);
}
#[test]
fn it_should_not_capture_stderr() {
    let workdir = init_test();
    let tested_method = &method(
        "variable_string_from_command",
        &["my_prefix", "my_variable", "echo 'foo' && echo 'bar' >&2"],
    )
    .enforce();
    let r = MethodTestSuite::new()
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Success]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Success);
    assert!(
        r.variables["my_prefix"]["my_variable"].is_string(),
        "The variable my_prefix.my_variable was not defined by the method execution",
    );
    assert_eq!(
        r.variables["my_prefix"]["my_variable"].as_str().unwrap(),
        "foo",
        "The variable my_prefix.my_variable was not defined by the method execution",
    );
    end_test(workdir);
}
