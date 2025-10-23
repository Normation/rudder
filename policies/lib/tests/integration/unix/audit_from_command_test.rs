use crate::integration::{end_test, get_lib_path, init_test};
use crate::testlib::method_test_suite::MethodTestSuite;
use crate::testlib::method_to_test::{MethodStatus, method};

#[test]
fn it_should_succeed_if_command_exit_code_is_compliant() {
    let workdir = init_test();
    let tested_method = &method("audit_from_command", &["/bin/true", "0"]).audit();

    let r = MethodTestSuite::new()
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Success]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Success);

    end_test(workdir);
}

#[test]
fn it_should_fail_if_command_exit_code_is_not_compliant() {
    let workdir = init_test();

    let tested_method = &method("audit_from_command", &["/bin/false", "0"]).audit();

    let r = MethodTestSuite::new()
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Error]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Error);

    end_test(workdir);
}

#[test]
fn it_should_succeed_if_command_exit_code_is_in_multiple_allowed() {
    let workdir = init_test();

    // Command: exit 1, allowed codes: 0,1
    let tested_method = &method("audit_from_command", &["/bin/false", "0,1"]).audit();

    let r = MethodTestSuite::new()
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Success]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Success);

    end_test(workdir);
}

#[test]
fn it_should_still_run_and_succeed_in_enforce_mode_if_exit_code_is_compliant() {
    let workdir = init_test();

    let tested_method = &method("audit_from_command", &["/bin/true", "0"]).enforce();

    let r = MethodTestSuite::new()
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Success]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Success);

    end_test(workdir);
}
