// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS

use crate::integration::{end_test, get_lib_path, init_test};
use crate::testlib::given::Given;
use crate::testlib::method_test_suite::MethodTestSuite;
use crate::testlib::method_to_test::{MethodStatus, method};

#[test]
fn it_should_define_the_variable_from_default_value() {
    let workdir = init_test();
    let tested_method = &method(
        "variable_string_default",
        &["my_prefix", "my_variable", "my_prefix.undefined", "foo"],
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
        "variable_string_default",
        &["my_prefix", "my_variable", "test.undefined", "foo"],
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
fn it_should_copy_the_source_variable_value_if_defined() {
    let workdir = init_test();
    let setup_method = &method("variable_string", &["my_prefix", "source", "foobar"]).enforce();
    let tested_method = &method(
        "variable_string_default",
        &["my_prefix", "my_variable", "my_prefix.source", "default"],
    )
    .audit();
    let r = MethodTestSuite::new()
        .given(Given::method_call(setup_method))
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
