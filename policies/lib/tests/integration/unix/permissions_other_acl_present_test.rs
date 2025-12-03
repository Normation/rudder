use crate::integration::{end_test, get_lib_path, init_test};
use crate::testlib::given::Given;
use crate::testlib::given::posix_acl_present::Perms;
use crate::testlib::method_test_suite::MethodTestSuite;
use crate::testlib::method_to_test::{MethodStatus, method};
use posix_acl::{PosixACL, Qualifier};

#[test]
fn it_should_add_other_acl_entry_to_file() {
    let workdir = init_test();
    let file_path = workdir.path().join("testfile.txt");
    let file_path_str = file_path.to_str().unwrap();

    let tested_method = &method(
        "permissions_other_acl_present",
        &[file_path_str, "false", "=rx"],
    )
    .enforce();

    let r = MethodTestSuite::new()
        .given(Given::file_present(file_path_str, "Dummy content\n"))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Repaired]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Repaired);

    assert_eq!(
        PosixACL::read_acl(file_path_str).unwrap().as_text(),
        "user::rw-\ngroup::r--\nmask::r--\nother::r-x\n",
        "Other ACL entry was not added as expected"
    );

    end_test(workdir);
}

#[test]
fn it_not_add_other_acl_entry_to_file_in_audit() {
    let workdir = init_test();
    let file_path = workdir.path().join("testfile.txt");
    let file_path_str = file_path.to_str().unwrap();

    let tested_method = &method(
        "permissions_other_acl_present",
        &[file_path_str, "false", "=rx"],
    )
    .audit();

    let r = MethodTestSuite::new()
        .given(Given::file_present(file_path_str, "Dummy content\n"))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Error]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Error);

    assert_eq!(
        PosixACL::read_acl(file_path_str).unwrap().as_text(),
        "user::rw-\ngroup::r--\nother::r--\n",
        "Other ACL entry was not added as expected"
    );

    end_test(workdir);
}
#[test]
fn it_success_in_audit_if_correct() {
    let workdir = init_test();
    let file_path = workdir.path().join("testfile.txt");
    let file_path_str = file_path.to_str().unwrap();

    let tested_method = &method(
        "permissions_other_acl_present",
        &[file_path_str, "false", "=r"],
    )
    .audit();

    let r = MethodTestSuite::new()
        .given(Given::file_present(file_path_str, "Dummy content\n"))
        .given(Given::posix_acl_present(
            file_path_str,
            Qualifier::Other,
            Perms::READ,
        ))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Success]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Success);

    assert_eq!(
        PosixACL::read_acl(file_path_str).unwrap().as_text(),
        "user::rw-\ngroup::r--\nmask::r--\nother::r--\n",
        "Other ACL entry was not added as expected"
    );

    end_test(workdir);
}
