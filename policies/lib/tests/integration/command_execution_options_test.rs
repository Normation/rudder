// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS

use crate::integration::{end_test, get_lib_path, init_test};
use crate::testlib::method_test_suite::MethodTestSuite;
use crate::testlib::method_to_test::{MethodStatus, method};
use uzers::{get_current_gid, get_current_groupname, get_current_uid, get_current_username};

#[test]
fn it_is_not_applicable_in_audit_mode() {
    let workdir = init_test();
    let file_path = workdir.path().join("target.txt");

    let tested_method = &method(
        "command_execution_options",
        &[
            &format!("/bin/touch {}", file_path.to_str().unwrap()),
            "",
            "false",
            "true",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            &get_current_uid().to_string(),
            &get_current_gid().to_string(),
            "",
            "",
            "",
            "",
            "true",
        ],
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
fn it_is_executed_in_audit_mode_if_asked_for() {
    let workdir = init_test();
    let file_path = workdir.path().join("target.txt");

    let tested_method = &method(
        "command_execution_options",
        &[
            &format!("/bin/touch {}", file_path.to_str().unwrap()),
            "",
            "true",
            "true",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            &get_current_uid().to_string(),
            &get_current_gid().to_string(),
            "",
            "",
            "",
            "",
            "true",
        ],
    )
    .audit();
    let r = MethodTestSuite::new()
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Success]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Success);
    assert!(
        file_path.exists(),
        "The file '{}' should have been created by the method execution",
        file_path.display()
    );
    end_test(workdir);
}

#[test]
fn it_is_executed_using_correct_uid() {
    let workdir = init_test();
    let file_path = workdir.path().join("target.txt");

    let tested_method = &method(
        "command_execution_options",
        &[
            &format!("id -u > {}", file_path.to_str().unwrap()),
            "",
            "",
            "true",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            &get_current_uid().to_string(),
            &get_current_gid().to_string(),
            "",
            "",
            "",
            "",
            "true",
        ],
    )
    .enforce();
    let r = MethodTestSuite::new()
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Repaired]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Repaired);

    let file_content = std::fs::read_to_string(file_path).unwrap();
    assert_eq!(
        file_content.trim(),
        &get_current_uid().to_string(),
        "File content does not match the expected output"
    );
    end_test(workdir);
}

#[test]
fn it_is_executed_using_correct_user() {
    let workdir = init_test();
    let file_path = workdir.path().join("target.txt");

    let tested_method = &method(
        "command_execution_options",
        &[
            &format!("id -un > {}", file_path.to_str().unwrap()),
            "",
            "",
            "true",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            &get_current_username()
                .unwrap()
                .to_string_lossy()
                .to_string(),
            "",
            "",
            "",
            "true",
        ],
    )
    .enforce();
    let r = MethodTestSuite::new()
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Repaired]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Repaired);

    let file_content = std::fs::read_to_string(file_path).unwrap();
    assert_eq!(
        file_content.trim(),
        &get_current_username()
            .unwrap()
            .to_string_lossy()
            .to_string(),
        "File content does not match the expected output"
    );
    end_test(workdir);
}

#[test]
fn it_is_executed_using_correct_gid() {
    let workdir = init_test();
    let file_path = workdir.path().join("target.txt");

    let tested_method = &method(
        "command_execution_options",
        &[
            &format!("id -g > {}", file_path.to_str().unwrap()),
            "",
            "",
            "true",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            &get_current_uid().to_string(),
            &get_current_gid().to_string(),
            "",
            "",
            "",
            "",
            "true",
        ],
    )
    .enforce();
    let r = MethodTestSuite::new()
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Repaired]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Repaired);

    let file_content = std::fs::read_to_string(file_path).unwrap();
    assert_eq!(
        file_content.trim(),
        &get_current_gid().to_string(),
        "File content does not match the expected output"
    );
    end_test(workdir);
}

#[test]
fn it_is_executed_using_correct_group() {
    let workdir = init_test();
    let file_path = workdir.path().join("target.txt");

    let tested_method = &method(
        "command_execution_options",
        &[
            &format!("id -gn > {}", file_path.to_str().unwrap()),
            "",
            "",
            "true",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            &get_current_groupname()
                .unwrap()
                .to_string_lossy()
                .to_string(),
            "",
            "",
            "true",
        ],
    )
    .enforce();
    let r = MethodTestSuite::new()
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Repaired]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Repaired);

    let file_content = std::fs::read_to_string(file_path).unwrap();
    assert_eq!(
        file_content.trim(),
        &get_current_groupname()
            .unwrap()
            .to_string_lossy()
            .to_string(),
        "File content does not match the expected output"
    );
    end_test(workdir);
}

#[test]
fn it_is_executed_using_correct_workdir() {
    let workdir = init_test();
    let file_path = workdir.path().join("target.txt");

    let tested_method = &method(
        "command_execution_options",
        &[
            &format!("pwd > {}", file_path.to_str().unwrap()),
            "",
            "",
            "true",
            "",
            workdir.path().to_str().unwrap(),
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            &get_current_uid().to_string(),
            &get_current_gid().to_string(),
            "",
            "",
            "",
            "",
            "true",
        ],
    )
    .enforce();
    let r = MethodTestSuite::new()
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Repaired]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Repaired);

    let file_content = std::fs::read_to_string(file_path).unwrap();
    assert_eq!(
        file_content.trim(),
        workdir.path().to_str().unwrap(),
        "File content does not match the expected output"
    );
    end_test(workdir);
}
#[test]
fn it_returns_an_error_if_the_timeout_is_exceeded() {
    let workdir = init_test();
    let tested_method = &method(
        "command_execution_options",
        &[
            "sleep 5",
            "",
            "",
            "true",
            "",
            "",
            "2",
            "",
            "",
            "",
            "",
            "",
            "",
            &get_current_uid().to_string(),
            &get_current_gid().to_string(),
            "",
            "",
            "",
            "",
            "true",
        ],
    )
    .enforce();
    let r = MethodTestSuite::new()
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Error]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Error);
    end_test(workdir);
}
#[test]
fn it_errors_if_args_is_malformed() {
    let workdir = init_test();
    let file_path = workdir.path().join("target.txt");

    let tested_method = &method(
        "command_execution_options",
        &[
            "touch",
            file_path.to_str().unwrap(),
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            &get_current_uid().to_string(),
            &get_current_gid().to_string(),
            "",
            "",
            "",
            "",
            "true",
        ],
    )
    .enforce();
    let r = MethodTestSuite::new()
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Error]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Error);

    assert!(
        !file_path.exists(),
        "File should not have been create by the method execution"
    );
    end_test(workdir);
}

#[test]
fn it_parses_multiple_args_parameter() {
    let workdir = init_test();
    let file_path = workdir.path().join("target.txt");
    let file_path2 = workdir.path().join("target2.txt");

    let tested_method = &method(
        "command_execution_options",
        &[
            "touch",
            &format!(
                r#"[ "{}", "{}"]"#,
                file_path.to_str().unwrap(),
                file_path2.to_str().unwrap()
            ),
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            &get_current_uid().to_string(),
            &get_current_gid().to_string(),
            "",
            "",
            "",
            "",
            "true",
        ],
    )
    .enforce();
    let r = MethodTestSuite::new()
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Repaired]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Repaired);

    assert!(
        file_path.exists(),
        "File should have been create by the method execution"
    );
    assert!(
        file_path2.exists(),
        "File should have been create by the method execution"
    );
    end_test(workdir);
}
#[test]
fn it_fails_when_using_the_args_parameter_and_the_use_shell_parameter() {
    let workdir = init_test();
    let file_path = workdir.path().join("target.txt");

    let tested_method = &method(
        "command_execution_options",
        &[
            "touch",
            file_path.to_str().unwrap(),
            "",
            "true",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            &get_current_uid().to_string(),
            &get_current_gid().to_string(),
            "",
            "",
            "",
            "",
            "true",
        ],
    )
    .enforce();
    let r = MethodTestSuite::new()
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Error]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Error);

    assert!(
        !file_path.exists(),
        "File should not have been create by the method execution"
    );
    end_test(workdir);
}
#[test]
fn it_uses_the_given_env_vars() {
    let workdir = init_test();
    let file_path = workdir.path().join("target.txt");

    let tested_method = &method(
        "command_execution_options",
        &[
            &format!(r#"echo "$FOO">"{}""#, file_path.to_str().unwrap()),
            "",
            "",
            "true",
            "",
            workdir.path().to_str().unwrap(),
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            &get_current_uid().to_string(),
            &get_current_gid().to_string(),
            "",
            "",
            "",
            r#"{ "FOO": "foo", "BAR": "bar" }"#,
            "true",
        ],
    )
    .enforce();
    let r = MethodTestSuite::new()
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Repaired]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Repaired);

    let file_content = std::fs::read_to_string(file_path).unwrap();
    assert_eq!(
        file_content.trim(),
        "foo",
        "File content does not match the expected output"
    );
    end_test(workdir);
}
