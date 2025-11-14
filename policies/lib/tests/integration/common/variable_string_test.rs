// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS

use crate::integration::{end_test, get_lib_path, init_test};
use crate::testlib::method_test_suite::MethodTestSuite;
use crate::testlib::method_to_test::{MethodStatus, method};

#[test]
fn it_should_define_the_variable() {
    let workdir = init_test();
    let tested_method =
        &method("variable_string", &["my_prefix", "my_variable", "foobar"]).enforce();
    let r = MethodTestSuite::new()
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Success]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Success);
    assert_eq!(
        r.variables["my_prefix"]["my_variable"].as_str().unwrap(),
        "foobar",
        "The variable my_prefix.my_variable was not defined by the method execution",
    );
    end_test(workdir);
}

#[test]
fn it_should_define_the_variable_in_audit() {
    let workdir = init_test();
    let tested_method = &method("variable_string", &["my_prefix", "my_variable", "foobar"]).audit();
    let r = MethodTestSuite::new()
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Success]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Success);
    assert_eq!(
        r.variables["my_prefix"]["my_variable"].as_str().unwrap(),
        "foobar",
        "The variable my_prefix.my_variable was not defined by the method execution",
    );
    end_test(workdir);
}

#[test]
fn it_should_always_define_string_variables() {
    let workdir = init_test();
    let tested_method = &method(
        "variable_string",
        &["my_prefix", "my_variable", r#"{"foo":"bar"}"#],
    )
    .enforce();
    let r = MethodTestSuite::new()
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Success]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Success);
    assert_eq!(
        r.variables["my_prefix"]["my_variable"].as_str().unwrap(),
        r#"{"foo":"bar"}"#,
        "The variable my_prefix.my_variable was not defined by the method execution",
    );
    end_test(workdir);
}
