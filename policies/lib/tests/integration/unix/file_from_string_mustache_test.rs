use crate::integration::{end_test, get_lib_path, init_test};
use crate::testlib::given::Given;
use crate::testlib::method_test_suite::MethodTestSuite;
use crate::testlib::method_to_test::{MethodStatus, method};

#[test]
fn it_should_render_template_to_new_file() {
    let workdir = init_test();
    let file_path = workdir.path().join("output.txt");
    let file_str = file_path.to_str().unwrap();

    let setup_method = &method(
        "variable_dict",
        &[
            "db",
            "users",
            r#"{ "Alice": { "name": "Alice", "age": "30" } }"#,
        ],
    )
    .enforce();

    let tested_method = &method(
        "file_from_string_mustache",
        &[
            r#"Hello, {{ vars.db.users.Alice.name }}! You are {{ vars.db.users.Alice.age }} years old."#,
            file_str,
        ],
    )
        .enforce();

    let r = MethodTestSuite::new()
        .given(Given::method_call(setup_method))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Repaired]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Repaired);

    let content = std::fs::read_to_string(file_str).unwrap();
    assert_eq!(
        content, "Hello, Alice! You are 30 years old.\n",
        "The rendered template content does not match the expected output"
    );

    end_test(workdir);
}

#[test]
fn it_should_overwrite_existing_file() {
    let workdir = init_test();
    let file_path = workdir.path().join("existing.txt");
    let file_str = file_path.to_str().unwrap();

    let setup_method = &method(
        "variable_dict",
        &["db", "users", r#"{ "Bob": { "name": "Bob" } }"#],
    )
    .enforce();

    let tested_method = &method(
        "file_from_string_mustache",
        &[r#"Welcome {{ vars.db.users.Bob.name }}!"#, file_str],
    )
    .enforce();

    let r = MethodTestSuite::new()
        .given(Given::file_present(
            file_str,
            "Previous content that should be overwritten",
        ))
        .given(Given::method_call(setup_method))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Repaired]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Repaired);

    let content = std::fs::read_to_string(file_str).unwrap();
    assert_eq!(
        content, "Welcome Bob!\n",
        "The file should have been overwritten with the rendered template"
    );

    end_test(workdir);
}

#[test]
fn it_should_not_change_file_in_audit_mode() {
    let workdir = init_test();
    let file_path = workdir.path().join("audit.txt");
    let file_str = file_path.to_str().unwrap();

    let setup_method = &method(
        "variable_dict",
        &["db", "info", r#"{ "msg": "should not appear" }"#],
    )
    .enforce();

    let tested_method = &method(
        "file_from_string_mustache",
        &[r#"Audit Test: {{ vars.db.info.msg }}"#, file_str],
    )
    .audit();

    let r = MethodTestSuite::new()
        .given(Given::file_present(
            file_str,
            "Previous content that should not be overwritten",
        ))
        .given(Given::method_call(setup_method))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    // In audit mode, it cannot modify the file, so it should return an Error
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Error]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Error);

    let content = std::fs::read_to_string(file_str).unwrap();
    assert_eq!(
        content, "Previous content that should not be overwritten",
        "The file should not have been overwritten with the rendered template"
    );

    end_test(workdir);
}

#[test]
fn it_should_render_loop_from_array() {
    let workdir = init_test();
    let file_path = workdir.path().join("array_render.txt");
    let file_str = file_path.to_str().unwrap();

    let setup_method = &method(
        "variable_dict",
        &[
            "app",
            "services",
            r#"{ "list": [ "nginx", "redis", "postgres" ] }"#,
        ],
    )
    .enforce();

    let tested_method = &method(
        "file_from_string_mustache",
        &[
            r#"Enabled services:
{{#vars.app.services.list}}
- {{.}}
{{/vars.app.services.list}}"#,
            file_str,
        ],
    )
    .enforce();

    let r = MethodTestSuite::new()
        .given(Given::method_call(setup_method))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Repaired]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Repaired);
    // Double newline at the end due to the templating
    let expected = r#"Enabled services:
- nginx
- redis
- postgres

"#;

    let content = std::fs::read_to_string(file_str).unwrap();
    assert_eq!(
        content, expected,
        "Rendered output did not match expected array rendering"
    );

    end_test(workdir);
}
