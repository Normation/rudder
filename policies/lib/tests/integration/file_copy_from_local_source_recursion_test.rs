// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS

use crate::integration::{end_test, get_lib_path, init_test};
use crate::testlib::given::Given;
use crate::testlib::method_test_suite::MethodTestSuite;
use crate::testlib::method_to_test::{MethodStatus, method};
use std::fs::read_to_string;

#[test]
fn it_copies_a_simple_file() {
    let workdir = init_test();
    let source_file = workdir.path().join("file_to_copy");
    let source_file_path = source_file.clone().to_string_lossy().into_owned();

    let destination_file = workdir.path().join("file_to_write");
    let destination_file_path = destination_file.clone().to_string_lossy().into_owned();

    let tested_method = &method(
        "file_copy_from_local_source_recursion",
        &[&source_file_path, &destination_file_path, "3"],
    )
    .enforce();
    let r = MethodTestSuite::new()
        .given(Given::file_present(&source_file_path, "Hello world 👋!"))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Repaired);
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Repaired]);
    assert!(
        destination_file.exists(),
        "The file '{}' should have been created by the method execution",
        destination_file.display()
    );
    assert_eq!(
        read_to_string(destination_file_path).unwrap(),
        "Hello world 👋!".to_string(),
        "The file '{}' content should have been updated by the method execution",
        destination_file.display()
    );
    end_test(workdir);
}
#[test]
fn it_does_not_copy_a_simple_file_in_audit() {
    let workdir = init_test();
    let source_file = workdir.path().join("file_to_copy");
    let source_file_path = source_file.clone().to_string_lossy().into_owned();

    let destination_file = workdir.path().join("file_to_write");
    let destination_file_path = destination_file.clone().to_string_lossy().into_owned();

    let tested_method = &method(
        "file_copy_from_local_source_recursion",
        &[&source_file_path, &destination_file_path, "3"],
    )
    .audit();
    let r = MethodTestSuite::new()
        .given(Given::file_present(&source_file_path, "Hello world 👋!"))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Error);
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Error]);
    assert!(
        !destination_file.exists(),
        "The file '{}' should have been created by the method execution",
        destination_file.display()
    );
    end_test(workdir);
}
#[test]
fn it_can_succeed_in_audit() {
    let workdir = init_test();
    let source_file = workdir.path().join("file_to_copy");
    let source_file_path = source_file.clone().to_string_lossy().into_owned();

    let destination_file = workdir.path().join("file_to_write");
    let destination_file_path = destination_file.clone().to_string_lossy().into_owned();

    let tested_method = &method(
        "file_copy_from_local_source_recursion",
        &[&source_file_path, &destination_file_path, "true"],
    )
    .audit();
    let r = MethodTestSuite::new()
        .given(Given::file_present(&source_file_path, "Hello world 👋!"))
        .given(Given::file_present(
            &destination_file_path,
            "Hello world 👋!",
        ))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Success);
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Success]);
    assert!(
        destination_file.exists(),
        "The file '{}' should have been created by the method execution",
        destination_file.display()
    );
    assert_eq!(
        read_to_string(destination_file_path).unwrap(),
        "Hello world 👋!".to_string(),
        "The file '{}' content should have been updated by the method execution",
        destination_file.display()
    );
    end_test(workdir);
}
#[test]
fn it_fails_if_the_source_file_does_not_exist() {
    let workdir = init_test();
    let source_file = workdir.path().join("file_to_copy");
    let source_file_path = source_file.clone().to_string_lossy().into_owned();

    let destination_file = workdir.path().join("file_to_write");
    let destination_file_path = destination_file.clone().to_string_lossy().into_owned();

    let tested_method = &method(
        "file_copy_from_local_source_recursion",
        &[&source_file_path, &destination_file_path, "3"],
    )
    .enforce();
    let r = MethodTestSuite::new()
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Error);
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Error]);
    assert!(
        !destination_file.exists(),
        "The file '{}' should have been created by the method execution",
        destination_file.display()
    );
    end_test(workdir);
}
#[test]
fn it_fails_if_the_source_file_does_not_exist_but_the_destination_file_does() {
    let workdir = init_test();
    let source_file = workdir.path().join("file_to_copy");
    let source_file_path = source_file.clone().to_string_lossy().into_owned();

    let destination_file = workdir.path().join("file_to_write");
    let destination_file_path = destination_file.clone().to_string_lossy().into_owned();

    let tested_method = &method(
        "file_copy_from_local_source_recursion",
        &[&source_file_path, &destination_file_path, "3"],
    )
    .enforce();
    let r = MethodTestSuite::new()
        .given(Given::file_present(
            &destination_file_path,
            "Hello world 👋!",
        ))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Error);
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Error]);
    assert!(
        destination_file.exists(),
        "The file '{}' should have been created by the method execution",
        destination_file.display()
    );
    assert_eq!(
        read_to_string(destination_file_path).unwrap(),
        "Hello world 👋!".to_string(),
        "The file '{}' content should have been updated by the method execution",
        destination_file.display()
    );
    end_test(workdir);
}
#[test]
fn it_fails_if_the_destination_file_is_a_folder() {
    let workdir = init_test();
    let source_file = workdir.path().join("file_to_copy");
    let source_file_path = source_file.clone().to_string_lossy().into_owned();

    let destination_file = workdir.path().join("file_to_write");
    let destination_file_path = destination_file.clone().to_string_lossy().into_owned();

    let tested_method = &method(
        "file_copy_from_local_source_recursion",
        &[&source_file_path, &destination_file_path, "inf"],
    )
    .enforce();
    let r = MethodTestSuite::new()
        .given(Given::directory_present(&destination_file_path))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Error);
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Error]);
    assert!(
        destination_file.is_dir(),
        "The file '{}' should still be a dir",
        destination_file.display()
    );
    end_test(workdir);
}
#[test]
fn it_edit_the_file_if_needed() {
    let workdir = init_test();
    let source_file = workdir.path().join("file_to_copy");
    let source_file_path = source_file.clone().to_string_lossy().into_owned();

    let destination_file = workdir.path().join("file_to_write");
    let destination_file_path = destination_file.clone().to_string_lossy().into_owned();

    let tested_method = &method(
        "file_copy_from_local_source_recursion",
        &[&source_file_path, &destination_file_path, "0"],
    )
    .enforce();
    let r = MethodTestSuite::new()
        .given(Given::file_present(&source_file_path, "Hello world 👋!"))
        .given(Given::file_present(
            &destination_file_path,
            "incorrect content\nonmultiple lines",
        ))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Repaired);
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Repaired]);
    assert!(
        destination_file.is_file(),
        "The file '{}' should still be a dir",
        destination_file.display()
    );
    end_test(workdir);
}
#[test]
fn it_can_copy_a_folder() {
    let workdir = init_test();
    let source_folder = workdir.path().join("folder_to_copy");
    let source_folder_path = source_folder.clone().to_string_lossy().into_owned();
    let binding = source_folder.join("file1.txt");
    let source_file = binding.to_str().unwrap();

    let destination_folder = workdir.path().join("folder_to_write");
    let destination_folder_path = destination_folder.clone().to_string_lossy().into_owned();
    let destination_file = destination_folder.join("file1.txt");
    let destination_file_path = destination_file.to_str().unwrap();

    let tested_method = &method(
        "file_copy_from_local_source_recursion",
        &[&source_folder_path, &destination_folder_path, "inf"],
    )
    .enforce();
    let r = MethodTestSuite::new()
        .given(Given::directory_present(&source_folder_path))
        .given(Given::file_present(source_file, "Hello world 👋!"))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Repaired);
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Repaired]);
    assert!(
        destination_folder.is_dir(),
        "The folder '{destination_folder_path}' should exists"
    );
    assert!(
        destination_file.is_file(),
        "The file '{destination_file_path}' should exists"
    );
    assert_eq!(
        read_to_string(destination_file_path).unwrap(),
        "Hello world 👋!".to_string(),
        "The file '{destination_file_path}' content should have been updated by the method execution"
    );
    end_test(workdir);
}

#[test]
fn it_can_detect_a_folder_is_not_a_copy_in_audit() {
    let workdir = init_test();
    let source_folder = workdir.path().join("folder_to_copy");
    let source_folder_path = source_folder.to_str().unwrap();
    let binding = source_folder.join("file1.txt");
    let source_file_path = binding.to_str().unwrap();

    let destination_folder = workdir.path().join("folder_to_write");
    let destination_folder_path = destination_folder.to_str().unwrap();
    let destination_file = destination_folder.join("file1.txt");
    let destination_file_path = destination_file.to_str().unwrap();

    let tested_method = &method(
        "file_copy_from_local_source_recursion",
        &[source_folder_path, destination_folder_path, "inf"],
    )
    .audit();
    let r = MethodTestSuite::new()
        .given(Given::directory_present(source_folder_path))
        .given(Given::file_present(source_file_path, "Hello world 👋!"))
        .given(Given::directory_present(destination_folder_path))
        .given(Given::file_present(
            destination_file_path,
            "Bye bye world 👋!",
        ))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Error);
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Error]);
    assert!(
        destination_folder.is_dir(),
        "The folder '{destination_folder_path}' should exists"
    );
    assert!(
        destination_file.is_file(),
        "The file '{destination_file_path}' should exists"
    );
    assert_eq!(
        read_to_string(destination_file_path).unwrap(),
        "Bye bye world 👋!".to_string(),
        "The file '{destination_file_path}' content should not have been updated by the method execution"
    );
    end_test(workdir);
}
