use crate::integration::{end_test, get_lib_path, init_test};
use crate::testlib::given::Given;
use crate::testlib::given::posix_acl_present::Perms;
use crate::testlib::method_test_suite::MethodTestSuite;
use crate::testlib::method_to_test::{MethodStatus, method};
use posix_acl::{PosixACL, Qualifier};
use uzers::{get_current_uid, get_user_by_uid};

#[test]
fn it_should_apply_mixed_acl_modifications() {
    let workdir = init_test();
    let file_path = workdir.path().join("testfile.txt");
    let file_path_str = file_path.to_str().unwrap();

    // Get current user and group names
    let current_uid = get_current_uid();
    let user = get_user_by_uid(current_uid).unwrap();
    let username = user.name().to_str().unwrap();

    let tested_method = &method(
        "permissions_acl_entry",
        &[
            file_path_str,
            "false", // not recursive
            &format!("*:-x,{username}:"),
            "*:+rw",
            "=r",
        ],
    )
    .enforce();

    let r = MethodTestSuite::new()
        .given(Given::file_present(file_path_str, "Dummy content\n"))
        .given(Given::posix_acl_present(
            file_path_str,
            Qualifier::User(current_uid),
            Perms::EXECUTE,
        ))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Repaired]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Repaired);

    let acl = PosixACL::read_acl(file_path_str).unwrap().as_text();

    assert!(
        acl.contains("user::rw-"),
        "Expected user owner permissions to be updated to rw-"
    );
    assert!(
        acl.contains(&format!("user:{username}:---")),
        "Expected ACL entry for user '{username}' to be reset"
    );
    assert!(
        acl.contains("group::rw-"),
        "Expected group permissions to include rw"
    );
    assert!(
        acl.contains("other::r--"),
        "Expected other permissions to be r--"
    );

    end_test(workdir);
}

#[test]
fn audit_should_pass_when_acl_already_set() {
    let workdir = init_test();
    let file_path = workdir.path().join("testfile_audit_pass.txt");
    let file_path_str = file_path.to_str().unwrap();

    let current_uid = get_current_uid();
    let user = get_user_by_uid(current_uid).unwrap();
    let username = user.name().to_str().unwrap();

    let tested_method = &method(
        "permissions_acl_entry",
        &[file_path_str, "false", &format!("{username}:-x"), "", ""],
    )
    .audit();

    let r = MethodTestSuite::new()
        .given(Given::file_present(file_path_str, "Audit content\n"))
        .given(Given::posix_acl_present(
            file_path_str,
            Qualifier::User(current_uid),
            Perms::READ,
        ))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    let acl = PosixACL::read_acl(file_path_str).unwrap().as_text();
    assert!(
        acl.contains(&format!("user:{username}:r--")),
        "Expected ACL entry for user '{username}' to be kept"
    );
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Success]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Success);
    end_test(workdir);
}

#[test]
fn audit_should_error_when_acl_is_not_already_set() {
    let workdir = init_test();
    let file_path = workdir.path().join("testfile_audit_pass.txt");
    let file_path_str = file_path.to_str().unwrap();

    let current_uid = get_current_uid();
    let user = get_user_by_uid(current_uid).unwrap();
    let username = user.name().to_str().unwrap();

    let tested_method = &method(
        "permissions_acl_entry",
        &[file_path_str, "false", &format!("{username}:-x"), "", ""],
    )
    .audit();

    let r = MethodTestSuite::new()
        .given(Given::file_present(file_path_str, "Audit content\n"))
        .given(Given::posix_acl_present(
            file_path_str,
            Qualifier::User(current_uid),
            Perms::EXECUTE,
        ))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    let acl = PosixACL::read_acl(file_path_str).unwrap().as_text();
    assert!(
        acl.contains(&format!("user:{username}:--x")),
        "Expected ACL entry for user '{username}' to be kept"
    );
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Error]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Error);
    end_test(workdir);
}
