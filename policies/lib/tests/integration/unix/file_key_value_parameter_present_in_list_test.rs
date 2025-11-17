use crate::integration::{end_test, get_lib_path, init_test};
use crate::testlib::given::Given;
use crate::testlib::method_test_suite::MethodTestSuite;
use crate::testlib::method_to_test::{MethodStatus, method};

#[test]
fn it_should_add_ssh_ciphers_key_and_parameter_if_missing() {
    let workdir = init_test();
    let file_path = workdir.path().join("sshd_config");
    let file_path_str = file_path.to_str().unwrap();

    let tested_method = &method(
        "file_key_value_parameter_present_in_list",
        &[file_path_str, "Ciphers", " ", "aes256-ctr", ",", "", ""],
    )
    .enforce();

    let r = MethodTestSuite::new()
        .given(Given::file_present(
            file_path_str,
            r#"
# SSH Daemon configuration
Port 22
PermitRootLogin no
"#,
        ))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Repaired]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Repaired);

    assert_eq!(
        std::fs::read_to_string(file_path_str).unwrap(),
        r#"
# SSH Daemon configuration
Port 22
PermitRootLogin no
Ciphers aes256-ctr
"#,
    );

    end_test(workdir);
}

#[test]
fn it_should_append_ssh_cipher_if_missing() {
    let workdir = init_test();
    let file_path = workdir.path().join("sshd_config");
    let file_path_str = file_path.to_str().unwrap();

    let tested_method = &method(
        "file_key_value_parameter_present_in_list",
        &[file_path_str, "Ciphers", " ", "aes256-ctr", ",", "", ""],
    )
    .enforce();

    let r = MethodTestSuite::new()
        .given(Given::file_present(
            file_path_str,
            r#"
# SSH Daemon configuration
Ciphers aes128-ctr,aes192-ctr
"#,
        ))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Repaired]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Repaired);

    assert_eq!(
        std::fs::read_to_string(file_path_str).unwrap(),
        r#"
# SSH Daemon configuration
Ciphers aes128-ctr,aes192-ctr,aes256-ctr
"#,
    );

    end_test(workdir);
}

#[test]
fn it_should_do_nothing_if_cipher_already_present() {
    let workdir = init_test();
    let file_path = workdir.path().join("sshd_config");
    let file_path_str = file_path.to_str().unwrap();

    let tested_method = &method(
        "file_key_value_parameter_present_in_list",
        &[file_path_str, "Ciphers", " ", "aes256-ctr", ",", "", ""],
    )
    .enforce();

    let r = MethodTestSuite::new()
        .given(Given::file_present(
            file_path_str,
            r#"
# SSH Daemon configuration
Ciphers aes128-ctr,aes192-ctr,aes256-ctr
"#,
        ))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Success]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Success);

    assert_eq!(
        std::fs::read_to_string(file_path_str).unwrap(),
        r#"
# SSH Daemon configuration
Ciphers aes128-ctr,aes192-ctr,aes256-ctr
"#,
    );

    end_test(workdir);
}

#[test]
fn it_should_nothing_if_cipher_already_present_with_quotes() {
    let workdir = init_test();
    let file_path = workdir.path().join("sshd_config");
    let file_path_str = file_path.to_str().unwrap();

    let tested_method = &method(
        "file_key_value_parameter_present_in_list",
        &[file_path_str, "Ciphers", " ", "aes256-ctr", ",", "\"", "\""],
    )
    .enforce();

    let r = MethodTestSuite::new()
        .given(Given::file_present(
            file_path_str,
            r#"
# SSH Configuration
Ciphers "aes128-ctr,aes192-ctr,aes256-ctr"
"#,
        ))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    assert_eq!(
        std::fs::read_to_string(file_path_str).unwrap(),
        r#"
# SSH Configuration
Ciphers "aes128-ctr,aes192-ctr,aes256-ctr"
"#,
    );

    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Success]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Success);

    end_test(workdir);
}
