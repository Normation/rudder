use crate::integration::{end_test, get_lib_path, init_test};
use crate::testlib::given::Given;
use crate::testlib::method_test_suite::MethodTestSuite;
use crate::testlib::method_to_test::{MethodStatus, method};

#[test]
fn it_should_expand_template_with_variable_dict_and_mustache() {
    let workdir = init_test();

    let template_path = workdir.path().join("sample.mustache");
    let target_path = workdir.path().join("output.txt");

    let setup_var_method = &method(
        "variable_dict",
        &[
            "example_service",
            "default",
            r#"{"name": "test-service", "port": "8080"}"#,
        ],
    )
    .enforce();

    let tested_method = &method(
        "file_from_template_type",
        &[
            template_path.to_str().unwrap(),
            target_path.to_str().unwrap(),
            "mustache",
        ],
    )
    .enforce();

    let r = MethodTestSuite::new()
        .given(Given::file_present(template_path.to_str().unwrap(), "Hello from {{vars.example_service.default.name}} on port {{vars.example_service.default.port}}!\n"))
        .given(Given::method_call(setup_var_method))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Repaired]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Repaired);

    let content = std::fs::read_to_string(target_path).unwrap();
    assert_eq!(content, "Hello from test-service on port 8080!\n");

    end_test(workdir);
}

#[test]
fn it_should_expand_template_with_variable_dict_and_cfengine() {
    let workdir = init_test();

    let template_path = workdir.path().join("sample.cfengine");
    let target_path = workdir.path().join("output.txt");

    let setup_var_method = &method(
        "variable_dict",
        &[
            "example_service",
            "default",
            r#"{"name": "test-service", "port": "8080"}"#,
        ],
    )
    .enforce();

    let tested_method = &method(
        "file_from_template_type",
        &[
            template_path.to_str().unwrap(),
            target_path.to_str().unwrap(),
            "cfengine",
        ],
    )
    .enforce();

    let r = MethodTestSuite::new()
        .given(Given::file_present(template_path.to_str().unwrap(), "Hello from $(example_service.default[name]) on port $(example_service.default[port])!\n"))
        .given(Given::method_call(setup_var_method))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Repaired]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Repaired);

    let content = std::fs::read_to_string(target_path).unwrap();
    assert_eq!(content, "Hello from test-service on port 8080!\n");

    end_test(workdir);
}
#[test]
fn it_should_audit_template_with_variable_dict_and_mustache() {
    let workdir = init_test();

    let template_path = workdir.path().join("sample.mustache");
    let target_path = workdir.path().join("output.txt");

    let setup_var_method = &method(
        "variable_dict",
        &[
            "example_service",
            "default",
            r#"{"name": "test-service", "port": "8080"}"#,
        ],
    )
    .audit();

    let tested_method = &method(
        "file_from_template_type",
        &[
            template_path.to_str().unwrap(),
            target_path.to_str().unwrap(),
            "mustache",
        ],
    )
    .enforce();

    let r = MethodTestSuite::new()
        .given(Given::file_present(target_path.to_str().unwrap(), "Hello from test-service on port 8080!\n"))
        .given(Given::file_present(template_path.to_str().unwrap(), "Hello from {{vars.example_service.default.name}} on port {{vars.example_service.default.port}}!\n"))
        .given(Given::method_call(setup_var_method))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Success]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Success);

    let content = std::fs::read_to_string(target_path).unwrap();
    assert_eq!(content, "Hello from test-service on port 8080!\n");

    end_test(workdir);
}
