use crate::integration::{end_test, get_lib_path, init_test};
use crate::testlib::given::Given;
use crate::testlib::method_test_suite::MethodTestSuite;
use crate::testlib::method_to_test::{MethodStatus, method};

#[test]
fn it_should_render_template_to_new_file() {
    let workdir = init_test();
    let file_path = workdir.path().join("output.txt");
    let file_str = file_path.to_str().unwrap();

    let tested_method = &method(
        "file_from_template_options",
        &[
            file_path.to_str().unwrap(),
            "minijinja",
            r#"{"foo": "foo", "bar": "bar", "foobar": "foobar"}"#,
            r#"{{foo}} + {{bar}} = {{foobar}}"#,
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

    let content = std::fs::read_to_string(file_str).unwrap();
    assert_eq!(
        content, "foo + bar = foobar",
        "The rendered template content does not match the expected output"
    );

    end_test(workdir);
}
#[test]
fn it_should_audit_template() {
    let workdir = init_test();
    let file_path = workdir.path().join("output.txt");
    let file_str = file_path.to_str().unwrap();

    let tested_method = &method(
        "file_from_template_options",
        &[
            file_path.to_str().unwrap(),
            "minijinja",
            r#"{"foo": "foo", "bar": "bar", "foobar": "foobar"}"#,
            r#"{{foo}} + {{bar}} = {{foobar}}"#,
            "",
            "true",
        ],
    )
    .audit();

    let r = MethodTestSuite::new()
        .given(Given::file_present(
            file_path.to_str().unwrap(),
            "Previous content that should be overwritten",
        ))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Error]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Error);

    let content = std::fs::read_to_string(file_str).unwrap();
    assert_eq!(
        content, "Previous content that should be overwritten",
        "The target file content should not have been updated"
    );

    end_test(workdir);
}
#[test]
fn it_should_render_template_to_new_file_using_datastate() {
    let workdir = init_test();
    let file_path = workdir.path().join("output.txt");
    let file_str = file_path.to_str().unwrap();

    let setup_method = &method(
        "variable_dict",
        &["db", "users", r#"{ "Bob": { "name": "Bob" } }"#],
    )
    .enforce();
    let tested_method = &method(
        "file_from_template_options",
        &[
            file_path.to_str().unwrap(),
            "minijinja",
            r#""#,
            r#"Welcome {{ vars.db.users.Bob.name }}!"#,
            "",
            "false",
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
        content, "Welcome Bob!",
        "The rendered template content does not match the expected output"
    );

    end_test(workdir);
}
#[test]
fn it_should_fail_if_not_template_is_given() {
    let workdir = init_test();
    let file_path = workdir.path().join("output.txt");

    let tested_method = &method(
        "file_from_template_options",
        &[
            file_path.to_str().unwrap(),
            "minijinja",
            r#"{"foo": "foo", "bar": "bar", "foobar": "foobar"}"#,
            "",
            "",
            "false",
        ],
    )
    .enforce();

    let r = MethodTestSuite::new()
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Error]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Error);

    assert!(!file_path.exists(), "The target file should not be created");
    end_test(workdir);
}
