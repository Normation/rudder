use crate::integration::{end_test, get_lib_path, init_test};
use crate::testlib::given::Given;
use crate::testlib::method_test_suite::MethodTestSuite;
use crate::testlib::method_to_test::{MethodStatus, method};

#[test]
fn it_should_succeed_if_directory_exists_in_audit_mode() {
    let workdir = init_test();
    let target_dir = workdir.path().join("my_dir");
    let target_str = target_dir.to_str().unwrap();

    let tested_method = &method("directory_check_exists", &[target_str]).audit();

    let r = MethodTestSuite::new()
        .given(Given::directory_present(target_str))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Success]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Success);

    end_test(workdir);
}

#[test]
fn it_should_return_error_if_directory_missing_in_audit_mode() {
    let workdir = init_test();
    let target_dir = workdir.path().join("missing_dir");
    let target_str = target_dir.to_str().unwrap();

    let tested_method = &method("directory_check_exists", &[target_str]).audit();

    let r = MethodTestSuite::new()
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Error]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Error);

    end_test(workdir);
}

#[test]
fn it_should_succeed_if_directory_exists_in_enforce_mode() {
    let workdir = init_test();
    let target_dir = workdir.path().join("existing");
    let target_str = target_dir.to_str().unwrap();

    let tested_method = &method("directory_check_exists", &[target_str]).enforce();

    let r = MethodTestSuite::new()
        .given(Given::directory_present(target_str))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Success]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Success);

    end_test(workdir);
}

#[test]
fn it_should_return_error_if_directory_missing_in_enforce_mode() {
    let workdir = init_test();
    let target_dir = workdir.path().join("new_dir");
    let target_str = target_dir.to_str().unwrap();

    let tested_method = &method("directory_check_exists", &[target_str]).enforce();

    let r = MethodTestSuite::new()
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Error]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Error);

    assert!(
        !target_dir.exists(),
        "Directory '{target_str}' should not be created by directory_check_exists"
    );

    end_test(workdir);
}

#[test]
fn it_should_return_error_if_target_exists_but_is_a_file() {
    let workdir = init_test();
    let target_file = workdir.path().join("not_a_dir");
    let target_str = target_file.to_str().unwrap();

    let tested_method = &method("directory_check_exists", &[target_str]).enforce();

    let r = MethodTestSuite::new()
        .given(Given::file_present(target_str, "I am not a directory"))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Error]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Error);

    end_test(workdir);
}
