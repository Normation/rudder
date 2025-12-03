// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS

use crate::integration::{end_test, get_lib_path, init_test};
use crate::testlib::given::Given;
use crate::testlib::method_test_suite::MethodTestSuite;
use crate::testlib::method_to_test::{MethodStatus, method};

#[test]
fn it_succeeds_in_enforce_when_the_target_file_exists() {
    let workdir = init_test();
    let file_path = &workdir
        .path()
        .join("flag_file")
        .to_string_lossy()
        .into_owned();
    let tested_method = &method("file_check_exists", &[file_path]).enforce();

    let r = MethodTestSuite::new()
        .given(Given::file_present(file_path, ""))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Success]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Success);
    end_test(workdir);
}

#[test]
fn it_fails_in_enforce_when_the_target_file_does_not_exist() {
    let workdir = init_test();
    let file_path = &workdir
        .path()
        .join("flag_file")
        .to_string_lossy()
        .into_owned();

    let tested_method = &method("file_check_exists", &[file_path]).enforce();
    let r = MethodTestSuite::new()
        .given(Given::file_absent(file_path))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Error]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Error);
    end_test(workdir);
}

#[test]
fn it_succeeds_in_audit_when_the_target_file_exists() {
    let workdir = init_test();
    let file_path = &workdir
        .path()
        .join("flag_file")
        .to_string_lossy()
        .into_owned();

    let tested_method = &method("file_check_exists", &[file_path]).audit();
    let r = MethodTestSuite::new()
        .given(Given::file_present(file_path, ""))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Success]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Success);
    end_test(workdir);
}
#[test]
fn it_succeeds_in_audit_when_the_target_file_does_not_exist() {
    let workdir = init_test();
    let file_path = &workdir
        .path()
        .join("flag_file")
        .to_string_lossy()
        .into_owned();

    let tested_method = &method("file_check_exists", &[file_path]).audit();
    let r = MethodTestSuite::new()
        .given(Given::file_absent(file_path))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Error]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Error);
    end_test(workdir);
}
#[test]
fn it_succeeds_in_enforce_when_the_target_is_a_directory() {
    let workdir = init_test();
    let dir_path = &workdir
        .path()
        .join("directory")
        .to_string_lossy()
        .into_owned();

    let tested_method = &method("file_check_exists", &[dir_path]).audit();
    let r = MethodTestSuite::new()
        .given(Given::directory_present(dir_path))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Success]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Success);
    end_test(workdir);
}
