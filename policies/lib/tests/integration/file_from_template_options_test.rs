use crate::integration::{end_test, get_lib_path, init_test};
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
