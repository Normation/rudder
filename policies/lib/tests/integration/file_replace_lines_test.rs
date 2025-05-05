use crate::integration::{end_test, get_lib_path, init_test};
use crate::testlib::given::Given;
use crate::testlib::method_test_suite::MethodTestSuite;
use crate::testlib::method_to_test::{MethodStatus, method};

#[test]
fn it_should_replace_a_matching_line() {
    let workdir = init_test();
    let file_path = workdir.path().join("sysctl.conf");
    let file_str = file_path.to_str().unwrap();

    let original = r#"# System config
net.ipv4.ip_forward=0
kernel.shmmax=1234
fs.file-max=100000
# End of config"#;

    let tested_method = &method(
        "file_replace_lines",
        &[
            file_str,
            r#"kernel\.shmmax=(?!5678$).*"#,
            r#"kernel.shmmax=5678"#,
        ],
    )
    .enforce();

    let r = MethodTestSuite::new()
        .given(Given::file_present(file_str, original))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Repaired]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Repaired);

    assert_eq!(
        std::fs::read_to_string(file_str).unwrap(),
        r#"# System config
net.ipv4.ip_forward=0
kernel.shmmax=5678
fs.file-max=100000
# End of config
"#,
        "The line should be correctly replaced"
    );

    end_test(workdir);
}

#[test]
fn it_should_do_nothing_if_already_correct() {
    let workdir = init_test();
    let file_path = workdir.path().join("already_good.conf");
    let file_str = file_path.to_str().unwrap();

    let content = r#"# System config
kernel.shmmax=5678
vm.swappiness=10"#;

    let tested_method = &method(
        "file_replace_lines",
        &[
            file_str,
            r#"kernel\.shmmax=(?!5678$).*"#,
            r#"kernel.shmmax=5678"#,
        ],
    )
    .enforce();

    let r = MethodTestSuite::new()
        .given(Given::file_present(file_str, content))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Success]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Success);

    assert_eq!(
        std::fs::read_to_string(file_str).unwrap(),
        content,
        "No change should be made"
    );

    end_test(workdir);
}

#[test]
fn it_should_use_capture_groups_in_replacement() {
    let workdir = init_test();
    let file_path = workdir.path().join("with_capture.conf");
    let file_str = file_path.to_str().unwrap();

    let original = r#"
# Limits
fs.inotify.max_user_watches=8192
# Other settings
"#;

    let tested_method = &method(
        "file_replace_lines",
        &[
            file_str,
            r#"fs\.inotify\.max_user_watches=(?!16384$).*"#,
            r#"fs.inotify.max_user_watches=16384"#,
        ],
    )
    .enforce();

    let r = MethodTestSuite::new()
        .given(Given::file_present(file_str, original))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Repaired]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Repaired);

    assert_eq!(
        std::fs::read_to_string(file_str).unwrap(),
        r#"
# Limits
fs.inotify.max_user_watches=16384
# Other settings
"#,
        "Captured value should be replaced correctly"
    );

    end_test(workdir);
}

#[test]
fn it_should_error_if_file_does_not_exist() {
    let workdir = init_test();
    let file_path = workdir.path().join("missing.conf");
    let file_str = file_path.to_str().unwrap();

    let tested_method =
        &method("file_replace_lines", &[file_str, r#"foo=.*"#, "foo=bar"]).enforce();

    let r = MethodTestSuite::new()
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Error]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Error);

    end_test(workdir);
}
#[test]
fn it_should_be_idempotent_if_well_formed() {
    let workdir = init_test();
    let file_path = workdir.path().join("with_capture.conf");
    let file_str = file_path.to_str().unwrap();

    let original = r#"
# Limits
fs.inotify.max_user_watches=8192
# Other settings
"#;

    let setup_method = &method(
        "file_replace_lines",
        &[
            file_str,
            r#"fs\.inotify\.max_user_watches=(?!16384$).*"#,
            r#"fs.inotify.max_user_watches=16384"#,
        ],
    )
    .enforce();
    let tested_method = &method(
        "file_replace_lines",
        &[
            file_str,
            r#"fs\.inotify\.max_user_watches=(?!16384$).*"#,
            r#"fs.inotify.max_user_watches=16384"#,
        ],
    )
    .enforce();

    let r = MethodTestSuite::new()
        .given(Given::file_present(file_str, original))
        .given(Given::method_call(setup_method))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    r.assert_legacy_result_conditions(
        tested_method,
        vec![MethodStatus::Success, MethodStatus::Repaired],
    );
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Success);

    assert_eq!(
        std::fs::read_to_string(file_str).unwrap(),
        r#"
# Limits
fs.inotify.max_user_watches=16384
# Other settings
"#,
        "Captured value should be replaced correctly"
    );

    end_test(workdir);
}
