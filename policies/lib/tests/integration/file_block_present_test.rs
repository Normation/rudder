// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS

use crate::integration::{end_test, get_lib_path, init_test};
use crate::testlib::given::Given;
use crate::testlib::method_test_suite::MethodTestSuite;
use crate::testlib::method_to_test::{MethodStatus, method};
use std::fs;

#[test]
fn it_should_edit_the_file_if_needed() {
    let workdir = init_test();
    let file = workdir.path().join("file_to_edit");
    let file_path = file.clone().to_string_lossy().into_owned();

    let tested_method = &method(
        "file_block_present",
        &[&file_path, "A block to add\non multiple lines"],
    )
    .enforce();
    let r = MethodTestSuite::new()
        .given(Given::file_present(&file_path, "Hello world!"))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Repaired]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Repaired);
    assert!(
        file.exists(),
        "The file '{}' should have been removed by the method execution",
        file.display()
    );
    assert_eq!(
        r#"Hello world!
A block to add
on multiple lines"#,
        fs::read_to_string(file).unwrap().trim_end(),
        "File content does not match the expected value"
    );
    end_test(workdir);
}
#[test]
fn it_should_error_in_audit_mode_if_the_file_is_not_correct() {
    let workdir = init_test();
    let file = workdir.path().join("file_to_edit");
    let file_path = file.clone().to_string_lossy().into_owned();

    let tested_method = &method(
        "file_block_present",
        &[&file_path, "A block to add\non multiple lines"],
    )
    .audit();
    let r = MethodTestSuite::new()
        .given(Given::file_present(&file_path, "Hello world!"))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Error]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Error);
    assert!(
        file.exists(),
        "The file '{}' should have been removed by the method execution",
        file.display()
    );
    assert_eq!(
        "Hello world!",
        fs::read_to_string(file).unwrap().trim_end(),
        "File content does not match the expected value"
    );
    end_test(workdir);
}
#[test]
fn it_should_be_idempotent() {
    let workdir = init_test();
    let file = workdir.path().join("file_to_edit");
    let file_path = file.clone().to_string_lossy().into_owned();

    let tested_method = &method(
        "file_block_present",
        &[&file_path, "A block to add\non multiple lines"],
    )
    .enforce();
    let tested_method2 = &method(
        "file_block_present",
        &[&file_path, "A block to add\non multiple lines"],
    )
    .enforce();
    let r = MethodTestSuite::new()
        .given(Given::file_present(&file_path, "Hello world!"))
        .given(Given::method_call(tested_method2))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(
        tested_method,
        vec![MethodStatus::Repaired, MethodStatus::Success],
    );
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Success);
    assert!(
        file.exists(),
        "The file '{}' should have been removed by the method execution",
        file.display()
    );
    assert_eq!(
        r#"Hello world!
A block to add
on multiple lines"#,
        fs::read_to_string(file).unwrap().trim_end(),
        "File content does not match the expected value"
    );
    end_test(workdir);
}
