// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS

use crate::integration::{end_test, get_lib_path, init_test};
use crate::testlib::given::Given;
use crate::testlib::method_test_suite::MethodTestSuite;
use crate::testlib::method_to_test::{MethodStatus, method};

#[test]
fn it_repairs_in_enforce_when_the_target_file_exists() {
    let workdir = init_test();
    let file = workdir.path().join("file_to_remove");
    let file_path = file.clone().to_string_lossy().into_owned();

    let tested_method = &method("file_absent", &[&file_path]).enforce();
    let r = MethodTestSuite::new()
        .given(Given::file_present(&file_path, ""))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Repaired]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Repaired);
    assert!(
        !file.exists(),
        "The file '{}' should have been removed by the method execution",
        file.display()
    );
    end_test(workdir);
}

#[test]
fn it_errors_in_audit_when_the_target_file_exists() {
    let workdir = init_test();
    let file = workdir.path().join("file_to_remove");
    let file_path = file.clone().to_string_lossy().into_owned();

    let tested_method = &method("file_absent", &[&file_path]).audit();
    let r = MethodTestSuite::new()
        .given(Given::file_present(&file_path, ""))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Error]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Error);
    assert!(
        file.exists(),
        "The file '{}' should NOT have been removed by the method execution",
        file.display()
    );
    end_test(workdir);
}

#[test]
fn it_errors_in_enforce_when_the_target_exists_and_is_a_directory() {
    let workdir = init_test();
    let dir = workdir.path().join("dir_to_remove");
    let file_path = dir.clone().to_string_lossy().into_owned();

    let tested_method = &method("file_absent", &[&file_path]).enforce();
    let r = MethodTestSuite::new()
        .given(Given::directory_present(&file_path))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Error]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Error);
    assert!(
        dir.exists(),
        "The directory '{}' should not have been removed by the method execution",
        dir.display()
    );
    end_test(workdir);
}

#[ignore]
#[test_log::test]
fn it_should_be_idempotent() {
    let workdir = init_test();
    let file = workdir.path().join("file_to_remove");
    let file_path = &file.clone().to_string_lossy().into_owned();
    let tested_method = &method("file_absent", &[file_path]).enforce();

    let r = MethodTestSuite::new()
        .given(Given::file_present(file_path, ""))
        .when(tested_method)
        .when(tested_method)
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(
        tested_method,
        vec![
            MethodStatus::Repaired,
            MethodStatus::Success,
            MethodStatus::Success,
        ],
    );
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Success);
    assert!(
        !file.exists(),
        "The file '{}' should have been removed by the method execution",
        file.display()
    );
    end_test(workdir);
}
