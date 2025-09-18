// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS

use crate::integration::{end_test, get_lib_path, init_test};
use crate::testlib::given::Given;
use crate::testlib::method_test_suite::MethodTestSuite;
use crate::testlib::method_to_test::{MethodStatus, MethodToTest};

#[test]
fn it_should_generate_the_true_conditions_if_needed() {
    let workdir = init_test();
    let variable_def = MethodToTest::variable_string(
        "my_prefix".to_string(),
        "my_name".to_string(),
        "hello world".to_string(),
    );
    let tested_method = MethodToTest::condition_from_variable_existence(
        "plouf".to_string(),
        "my_prefix.my_name".to_string(),
    )
    .enforce();
    let r = MethodTestSuite::new()
        .given(Given::method_call(variable_def))
        .when(tested_method.clone())
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method.clone(), vec![MethodStatus::Success]);
    r.assert_log_v4_result_conditions(tested_method.clone(), MethodStatus::Success);
    r.assert_conditions_are_defined(vec!["plouf_true".to_string()]);
    r.assert_conditions_are_undefined(vec!["plouf_false".to_string()]);
    end_test(workdir);
}
#[test]
fn it_should_generate_the_false_conditions_if_needed() {
    let workdir = init_test();
    let variable_def = MethodToTest::variable_string(
        "my_prefix".to_string(),
        "my_name".to_string(),
        "hello world".to_string(),
    );
    let tested_method = MethodToTest::condition_from_variable_existence(
        "plouf".to_string(),
        "my_prefix.my_wrong_name".to_string(),
    )
    .enforce();
    let r = MethodTestSuite::new()
        .given(Given::method_call(variable_def))
        .when(tested_method.clone())
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method.clone(), vec![MethodStatus::Success]);
    r.assert_log_v4_result_conditions(tested_method.clone(), MethodStatus::Success);
    r.assert_conditions_are_defined(vec!["plouf_false".to_string()]);
    r.assert_conditions_are_undefined(vec!["plouf_true".to_string()]);
    end_test(workdir);
}
#[test]
fn it_should_work_on_dict_variable() {
    let workdir = init_test();
    let variable_def = MethodToTest::variable_dict(
        "my_prefix".to_string(),
        "my_name".to_string(),
        r#"{"key": "hello world"}"#.to_string(),
    );
    let tested_method = MethodToTest::condition_from_variable_existence(
        "plouf".to_string(),
        "my_prefix.my_name".to_string(),
    )
    .enforce();
    let r = MethodTestSuite::new()
        .given(Given::method_call(variable_def))
        .when(tested_method.clone())
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method.clone(), vec![MethodStatus::Success]);
    r.assert_log_v4_result_conditions(tested_method.clone(), MethodStatus::Success);
    r.assert_conditions_are_defined(vec!["plouf_true".to_string()]);
    r.assert_conditions_are_undefined(vec!["plouf_false".to_string()]);
    end_test(workdir);
}
