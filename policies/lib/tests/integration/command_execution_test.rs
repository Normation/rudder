// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS

use crate::integration::{end_test, get_lib_path, init_test};
use crate::testlib::method_test_suite::MethodTestSuite;
use crate::testlib::method_to_test::{MethodStatus, method};

#[test]
fn it_is_not_applicable_in_audit_mode() {
    let workdir = init_test();
    let file_path = workdir.path().join("target.txt");

    let tested_method = &method(
        "command_execution",
        &[&format!("/bin/touch {}", file_path.to_str().unwrap())],
    )
    .audit();
    let r = MethodTestSuite::new()
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::NA]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::NA);
    assert!(
        !file_path.exists(),
        "The file '{}' should not have been created by the method execution",
        file_path.display()
    );
    end_test(workdir);
}
#[test]
fn it_repairs_in_enforced_mode_if_the_command_succeeds() {
    let workdir = init_test();
    let file_path = workdir.path().join("target.txt");

    let tested_method = &method(
        "command_execution",
        &[&format!("/bin/touch {}", file_path.to_str().unwrap())],
    )
    .enforce();
    let r = MethodTestSuite::new()
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Repaired]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Repaired);
    assert!(
        file_path.exists(),
        "The file '{}' should have been created by the method execution",
        file_path.display()
    );
    end_test(workdir);
}
#[test]
fn it_errors_in_enforced_mode_if_the_command_fails() {
    let workdir = init_test();
    let file_path = workdir.path().join("nonexistingfolder/target.txt");

    let tested_method = &method(
        "command_execution",
        &[&format!("/bin/touch {}", file_path.to_str().unwrap())],
    )
    .enforce();
    let r = MethodTestSuite::new()
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Error]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Error);
    assert!(
        !file_path.exists(),
        "The file '{}' should not have been created by the method execution",
        file_path.display()
    );
    end_test(workdir);
}
