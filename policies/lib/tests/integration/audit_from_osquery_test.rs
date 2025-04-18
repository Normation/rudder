// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS

use crate::integration::{end_test, get_lib_path, init_test};
use crate::testlib::method_test_suite::MethodTestSuite;
use crate::testlib::method_to_test::{MethodStatus, method};

// Tests are ignored by default as they assume osquery is installed.
// You can run them using  cargo nextest run --run-ignored all -- integration::audit_from_osquery_test
#[ignore]
#[test]
fn it_should_run_the_query_and_compare_the_result() {
    let workdir = init_test();
    let tested_method = &method(
        "audit_from_osquery",
        &["select arch from os_version;", "=", "x86_64"],
    )
    .enforce();
    let r = MethodTestSuite::new()
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Success]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Success);
    end_test(workdir);
}
#[ignore]
#[test]
fn it_should_work_in_audit() {
    let workdir = init_test();
    let tested_method = &method(
        "audit_from_osquery",
        &["select arch from os_version;", "=", "x86_64"],
    )
    .audit();
    let r = MethodTestSuite::new()
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Success]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Success);
    end_test(workdir);
}
#[ignore]
#[test]
fn it_should_error_if_the_query_is_incorrect() {
    let workdir = init_test();
    let tested_method = &method(
        "audit_from_osquery",
        &["select arch from os_versionN;", "=", "x86_64"],
    )
    .enforce();
    let r = MethodTestSuite::new()
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Error]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Error);
    end_test(workdir);
}
