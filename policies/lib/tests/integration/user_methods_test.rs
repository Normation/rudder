// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS

use crate::integration::{end_test, get_lib_path, init_test};
use crate::testlib::given::Given;
use crate::testlib::method_test_suite::MethodTestSuite;
use crate::testlib::method_to_test::{MethodStatus, method};
use uzers::get_user_by_name;

#[test]
fn user_is_missing() {
    let workdir = init_test();
    let user_name = "toto".to_string();

    let tested_method = &method("user_absent", &[&user_name]).enforce();

    let r = MethodTestSuite::new()
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    r.assert_legacy_result_conditions(&tested_method.clone(), vec![MethodStatus::Success]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Success);

    assert!(
        get_user_by_name(&user_name).is_none(),
        "check that user toto is missing"
    );

    end_test(workdir);
}

#[test]
#[ignore = "need to run as root"]
fn add_user() {
    let workdir = init_test();
    let user_name = "toto".to_string();

    let tested_method = &method("user_present", &[&user_name]).enforce();

    let r = MethodTestSuite::new()
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    r.assert_legacy_result_conditions(&tested_method.clone(), vec![MethodStatus::Repaired]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Repaired);

    assert!(
        get_user_by_name(&user_name).is_some(),
        "check that user toto was created"
    );

    end_test(workdir);
}

#[test]
#[ignore = "need to run as root"]
fn remove_user() {
    let workdir = init_test();
    let user_name = "toto".to_string();

    let add_user = &method("user_present", &[&user_name]).enforce();

    let tested_method = &method("user_absent", &[&user_name]).enforce();

    let r = MethodTestSuite::new()
        .given(Given::method_call(add_user))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    r.assert_legacy_result_conditions(&tested_method.clone(), vec![MethodStatus::Repaired]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Repaired);

    assert!(
        get_user_by_name(&user_name).is_none(),
        "check that user toto was removed"
    );

    end_test(workdir);
}
