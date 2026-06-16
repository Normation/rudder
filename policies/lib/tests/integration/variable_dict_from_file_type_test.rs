// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

use crate::integration::{end_test, get_lib_path, init_test};
use crate::testlib::given::Given;
use crate::testlib::method_test_suite::MethodTestSuite;
use crate::testlib::method_to_test::{MethodStatus, method};

#[test]
fn it_should_define_the_json_default_variable() {
    let workdir = init_test();

    let target_dir = workdir.path().join("existing");
    let target_str = target_dir.to_str().unwrap();

    let tested_method = &method(
        "variable_dict_from_file_type",
        &["my_prefix", "my_variable", target_str, ""],
    )
    .enforce();
    let r = MethodTestSuite::new()
        .given(Given::file_present(target_str, "{ \"key1\": \"value1\" }"))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Success]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Success);
    assert_eq!(
        r.variables["my_prefix"]["my_variable"]["key1"]
            .as_str()
            .unwrap(),
        "value1",
        "The variable my_prefix.my_variable was not defined by the method execution",
    );
    end_test(workdir);
}

#[test]
fn it_should_define_the_json_variable() {
    let workdir = init_test();

    let target_dir = workdir.path().join("existing");
    let target_str = target_dir.to_str().unwrap();

    let tested_method = &method(
        "variable_dict_from_file_type",
        &["my_prefix", "my_variable", target_str, "JSON"],
    )
    .enforce();
    let r = MethodTestSuite::new()
        .given(Given::file_present(target_str, "{ \"key1\": \"value1\" }"))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Success]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Success);
    assert_eq!(
        r.variables["my_prefix"]["my_variable"]["key1"]
            .as_str()
            .unwrap(),
        "value1",
        "The variable my_prefix.my_variable was not defined by the method execution",
    );
    end_test(workdir);
}

#[test]
fn it_should_define_the_yaml_variable() {
    let workdir = init_test();

    let target_dir = workdir.path().join("existing.yml");
    let target_str = target_dir.to_str().unwrap();

    let tested_method = &method(
        "variable_dict_from_file_type",
        &["my_prefix", "my_variable", target_str, ""],
    )
    .enforce();
    let r = MethodTestSuite::new()
        .given(Given::file_present(target_str, "key1: value1"))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Success]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Success);
    assert_eq!(
        r.variables["my_prefix"]["my_variable"]["key1"]
            .as_str()
            .unwrap(),
        "value1",
        "The variable my_prefix.my_variable was not defined by the method execution",
    );
    end_test(workdir);
}

#[test]
fn it_should_define_the_env_variable() {
    let workdir = init_test();

    let target_dir = workdir.path().join("existing");
    let target_str = target_dir.to_str().unwrap();

    let tested_method = &method(
        "variable_dict_from_file_type",
        &["my_prefix", "my_variable", target_str, "ENV"],
    )
    .enforce();
    let r = MethodTestSuite::new()
        .given(Given::file_present(
            target_str,
            "key1=value1
key2=\"value2\"",
        ))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Success]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Success);
    assert_eq!(
        r.variables["my_prefix"]["my_variable"]["key1"]
            .as_str()
            .unwrap(),
        "value1",
        "The variable my_prefix.my_variable was not defined by the method execution",
    );
    assert_eq!(
        r.variables["my_prefix"]["my_variable"]["key2"]
            .as_str()
            .unwrap(),
        "value2",
        "The variable my_prefix.my_variable was not defined by the method execution",
    );
    end_test(workdir);
}
