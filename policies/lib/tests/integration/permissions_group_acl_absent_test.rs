use crate::integration::{end_test, get_lib_path, init_test};
use crate::testlib::given::Given;
use crate::testlib::given::posix_acl_present::Perms;
use crate::testlib::method_test_suite::MethodTestSuite;
use crate::testlib::method_to_test::{MethodStatus, method};
use posix_acl::{PosixACL, Qualifier};
use uzers::{get_current_uid, get_user_by_uid};

#[ignore]
#[test]
fn it_should_remove_group_acl_entry_from_file() {
    let workdir = init_test();
    let file_path = workdir.path().join("testfile.txt");
    let file_path_str = file_path.to_str().unwrap();

    let current_gid = get_user_by_uid(get_current_uid())
        .unwrap()
        .primary_group_id();
    let tested_method = &method(
        "permissions_group_acl_absent",
        &[
            file_path_str,
            "false",
            uzers::get_current_groupname().unwrap().to_str().unwrap(),
        ],
    )
    .enforce();

    let r = MethodTestSuite::new()
        .given(Given::file_present(file_path_str, "Dummy content\n"))
        .given(Given::posix_acl_present(
            file_path_str,
            Qualifier::Group(
                get_user_by_uid(get_current_uid())
                    .unwrap()
                    .primary_group_id(),
            ),
            Perms::READ,
        ))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Repaired]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Repaired);

    assert_eq!(
        PosixACL::read_acl(file_path_str).unwrap().as_text(),
        "user::rw-\ngroup::r--\nmask::r--\nother::r--\n",
        "Group ACL entry for GID {current_gid} was not removed as expected"
    );

    end_test(workdir);
}
#[ignore]
#[test]
fn it_should_not_remove_group_acl_entry_from_file_in_audit() {
    let workdir = init_test();
    let file_path = workdir.path().join("testfile.txt");
    let file_path_str = file_path.to_str().unwrap();

    let current_gid = get_user_by_uid(get_current_uid())
        .unwrap()
        .primary_group_id();
    let current_group_name = uzers::get_current_groupname()
        .unwrap()
        .into_string()
        .unwrap();
    let tested_method = &method(
        "permissions_group_acl_absent",
        &[file_path_str, "false", &current_group_name],
    )
    .audit();

    let r = MethodTestSuite::new()
        .given(Given::file_present(file_path_str, "Dummy content\n"))
        .given(Given::posix_acl_present(
            file_path_str,
            Qualifier::Group(
                get_user_by_uid(get_current_uid())
                    .unwrap()
                    .primary_group_id(),
            ),
            Perms::READ,
        ))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Error]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Error);

    assert_eq!(
        PosixACL::read_acl(file_path_str).unwrap().as_text(),
        format!(
            "user::rw-\ngroup::r--\ngroup:{current_group_name}:r--\nmask::r--\nother::r--\n"
        ),
        "Group ACL entry for GID {} was not removed as expected",
        current_gid
    );

    end_test(workdir);
}
#[ignore]
#[test]
fn it_should_success_in_audit_if_the_acl_is_absent() {
    let workdir = init_test();
    let file_path = workdir.path().join("testfile.txt");
    let file_path_str = file_path.to_str().unwrap();

    let current_group_name = uzers::get_current_groupname()
        .unwrap()
        .into_string()
        .unwrap();
    let current_user_name = uzers::get_current_username()
        .unwrap()
        .into_string()
        .unwrap();
    let tested_method = &method(
        "permissions_group_acl_absent",
        &[file_path_str, "false", &current_group_name],
    )
    .audit();

    let r = MethodTestSuite::new()
        .given(Given::file_present(file_path_str, "Dummy content\n"))
        .given(Given::posix_acl_present(
            file_path_str,
            Qualifier::User(get_current_uid()),
            Perms::READ,
        ))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Success]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Success);

    assert_eq!(
        PosixACL::read_acl(file_path_str).unwrap().as_text(),
        format!(
            "user::rw-\nuser:{current_user_name}:r--\ngroup::r--\nmask::r--\nother::r--\n"
        ),
        "Group ACL entry for GID {} was somehow present",
        current_group_name
    );

    end_test(workdir);
}
