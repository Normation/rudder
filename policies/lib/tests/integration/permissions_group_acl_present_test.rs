use crate::integration::{end_test, get_lib_path, init_test};
use crate::testlib::given::Given;
use crate::testlib::method_test_suite::MethodTestSuite;
use crate::testlib::method_to_test::{MethodStatus, method};
use posix_acl::PosixACL;
use uzers::{get_current_uid, get_user_by_uid};

#[test]
fn it_should_add_group_acl_entry_to_file() {
    let workdir = init_test();
    let file_path = workdir.path().join("testfile.txt");
    let file_path_str = file_path.to_str().unwrap();

    let group_name = uzers::get_current_groupname().unwrap();
    let current_gid = get_user_by_uid(get_current_uid())
        .unwrap()
        .primary_group_id();

    let tested_method = &method(
        "permissions_group_acl_present",
        &[file_path_str, "false", group_name.to_str().unwrap(), "=r"],
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
        format!(
            "user::rw-\ngroup::r--\ngroup:{}:r--\nmask::r--\nother::r--\n",
            group_name.into_string().unwrap()
        ),
        "Group ACL entry for GID {} was not added as expected",
        current_gid
    );

    end_test(workdir);
}
