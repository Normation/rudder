// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS

use crate::integration::{end_test, get_lib_path, init_test};
use crate::testlib::given::Given;
use crate::testlib::method_test_suite::MethodTestSuite;
use crate::testlib::method_to_test::{MethodStatus, method};

#[test]
fn it_should_generate_the_true_conditions_if_needed() {
    let workdir = init_test();
    let tested_method = &method("condition_from_expression", &["plouf", "(any.!false)"]).enforce();
    let r = &MethodTestSuite::new()
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
    let tested_method = &method("condition_from_expression", &["plouf", "!(any.!false)"]).enforce();
    let r = MethodTestSuite::new()
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Success]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Success);
    r.assert_conditions_are_defined(vec!["plouf_false".to_string()]);
    r.assert_conditions_are_undefined(vec!["plouf_true".to_string()]);
    end_test(workdir);
}
#[test]
fn calls_should_be_independent() {
    let workdir = init_test();
    let tested_method1 =
        &method("condition_from_expression", &["plouf", "!(any.!false)"]).enforce();
    let tested_method2 = &method("condition_from_expression", &["plouf", "(any.!false)"]).enforce();
    let r = MethodTestSuite::new()
        .given(Given::method_call(tested_method2))
        .when(tested_method1)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method1, vec![MethodStatus::Success]);
    r.assert_log_v4_result_conditions(tested_method1, MethodStatus::Success);
    r.assert_conditions_are_defined(vec!["plouf_false".to_string(), "plouf_true".to_string()]);
    end_test(workdir);
}
