// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

use crate::integration::{end_test, get_lib_path, init_test};
use crate::testlib::given::Given;
use crate::testlib::method_test_suite::MethodTestSuite;
use crate::testlib::method_to_test::{MethodStatus, method};

#[test]
fn it_errors_in_audit_when_the_directory_exists() {
    let workdir = init_test();
    let dir = workdir.path().join("dir_to_remove");
    let dir_path = dir.to_str().unwrap();

    let tested_method = &method("directory_absent", &[dir_path, "false"]).audit();
    let r = MethodTestSuite::new()
        .given(Given::directory_present(dir_path))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Error]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Error);
    assert!(
        dir.exists(),
        "The directory '{}' should not have been removed in audit mode",
        dir.display()
    );
    end_test(workdir);
}

#[test]
fn it_does_not_remove_a_non_empty_directory_in_audit_when_recursive() {
    let workdir = init_test();
    let dir = workdir.path().join("dir_to_remove");
    let dir_path = dir.to_str().unwrap();
    let child = dir.join("child.txt");
    let child_path = child.to_str().unwrap();

    let tested_method = &method("directory_absent", &[dir_path, "true"]).audit();
    let r = MethodTestSuite::new()
        .given(Given::directory_present(dir_path))
        .given(Given::file_present(child_path, "some content"))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Error]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Error);
    assert!(
        child.exists(),
        "The file '{}' should not have been removed in audit mode",
        child.display()
    );
    end_test(workdir);
}

#[test]
fn it_repairs_a_non_empty_directory_in_enforce_when_recursive() {
    let workdir = init_test();
    let dir = workdir.path().join("dir_to_remove");
    let dir_path = dir.to_str().unwrap();
    let child_path = dir.join("child.txt");

    let tested_method = &method("directory_absent", &[dir_path, "true"]).enforce();
    let r = MethodTestSuite::new()
        .given(Given::directory_present(dir_path))
        .given(Given::file_present(
            child_path.to_str().unwrap(),
            "some content",
        ))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Repaired]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Repaired);
    assert!(
        !dir.exists(),
        "The directory '{}' should have been removed",
        dir.display()
    );
    end_test(workdir);
}
