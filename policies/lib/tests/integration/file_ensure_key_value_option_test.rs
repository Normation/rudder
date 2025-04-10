use crate::integration::{end_test, get_lib_path, init_test};
use crate::testlib::given::Given;
use crate::testlib::method_test_suite::MethodTestSuite;
use crate::testlib::method_to_test::{MethodStatus, method};

#[test]
fn it_should_create_key_value_pair_if_not_present_in_strict_mode() {
    let workdir = init_test();
    let file_path = workdir.path().join("test_file.txt");
    let file_str = file_path.to_str().unwrap();

    let key = "key1";
    let value = "value1";
    let separator = "=";
    let option = "strict";

    let tested_method = &method(
        "file_ensure_key_value_option",
        &[file_str, key, value, separator, option],
    )
    .enforce();

    let r = MethodTestSuite::new()
        .given(Given::file_present(
            file_str,
            r#"
# This is some unrelated comment or metadata
# In a real-world scenario, files have extra context
This is a sample config file with random text.
"#,
        ))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Repaired]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Repaired);

    let file_contents = std::fs::read_to_string(file_str).unwrap();
    assert_eq!(
        file_contents,
        r#"
# This is some unrelated comment or metadata
# In a real-world scenario, files have extra context
This is a sample config file with random text.
key1=value1
"#,
        "The file content was not updated correctly with the new key-value pair."
    );

    end_test(workdir);
}

#[test]
fn it_should_update_value_of_existing_key_in_strict_mode() {
    let workdir = init_test();
    let file_path = workdir.path().join("test_file.txt");
    let file_str = file_path.to_str().unwrap();

    let key = "key1";
    let value = "new_value";
    let separator = "=";
    let option = "strict";

    let tested_method = &method(
        "file_ensure_key_value_option",
        &[file_str, key, value, separator, option],
    )
    .enforce();

    let r = MethodTestSuite::new()
        .given(Given::file_present(
            file_str,
            r#"
# Some random header text
# More metadata before the actual config lines
key1=value1
"#,
        ))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Repaired]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Repaired);

    let file_contents = std::fs::read_to_string(file_str).unwrap();
    assert_eq!(
        file_contents,
        r#"
# Some random header text
# More metadata before the actual config lines
key1=new_value
"#,
        "The value for the existing key was not updated correctly."
    );

    end_test(workdir);
}

#[test]
fn it_should_create_key_value_pair_with_lax_option() {
    let workdir = init_test();
    let file_path = workdir.path().join("test_file.txt");
    let file_str = file_path.to_str().unwrap();

    let key = "key1";
    let value = "value1";
    let separator = "=";
    let option = "lax";

    let tested_method = &method(
        "file_ensure_key_value_option",
        &[file_str, key, value, separator, option],
    )
    .enforce();

    let r = MethodTestSuite::new()
        .given(Given::file_present(
            file_str,
            r#"
# This is a sample configuration file
# Random lines and unrelated metadata here
Other config data might be mixed here as well.
"#,
        ))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Repaired]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Repaired);

    let file_contents = std::fs::read_to_string(file_str).unwrap();
    assert_eq!(
        file_contents,
        r#"
# This is a sample configuration file
# Random lines and unrelated metadata here
Other config data might be mixed here as well.
key1=value1
"#,
        "The file content was not updated correctly with the new key-value pair allowing spaces."
    );

    end_test(workdir);
}

#[test]
fn it_should_update_value_in_lax_mode_if_key_present_with_spaces_around_separator() {
    let workdir = init_test();
    let file_path = workdir.path().join("test_file.txt");
    let file_str = file_path.to_str().unwrap();

    let key = "key1";
    let value = "new_value";
    let separator = "=";
    let option = "lax";

    let tested_method = &method(
        "file_ensure_key_value_option",
        &[file_str, key, value, separator, option],
    )
    .enforce();

    let r = MethodTestSuite::new()
        .given(Given::file_present(
            file_str,
            r#"
# This section defines some important parameters
key1    =   old_value
"#,
        ))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Repaired]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Repaired);

    let file_contents = std::fs::read_to_string(file_str).unwrap();
    assert_eq!(
        file_contents,
        r#"
# This section defines some important parameters
key1    =   new_value
"#,
        "The value for the existing key was not updated correctly with spaces allowed."
    );

    end_test(workdir);
}

#[test]
fn it_should_not_change_key_if_it_is_not_at_beginning_of_line() {
    let workdir = init_test();
    let file_path = workdir.path().join("test_file.txt");
    let file_str = file_path.to_str().unwrap();

    let key = "key1";
    let value = "new_value";
    let separator = "=";
    let option = "strict";

    let tested_method = &method(
        "file_ensure_key_value_option",
        &[file_str, key, value, separator, option],
    )
    .enforce();

    let r = MethodTestSuite::new()
        .given(Given::file_present(
            file_str,
            r#"
# This file contains multiple configuration parameters
a line containing key1=value1
    key2=value2
"#,
        ))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Repaired]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Repaired);

    let file_contents = std::fs::read_to_string(file_str).unwrap();
    assert_eq!(
        file_contents,
        r#"
# This file contains multiple configuration parameters
a line containing key1=value1
    key2=value2
key1=new_value
"#,
        "The file content should not have changed since the key is not at the beginning of the line."
    );

    end_test(workdir);
}

#[test]
fn it_should_change_all_occurrences_when_key_separator_pattern_is_present_multiple_times() {
    let workdir = init_test();
    let file_path = workdir.path().join("test_file.txt");
    let file_str = file_path.to_str().unwrap();

    let key = "key1";
    let value = "new_value";
    let separator = "=";
    let option = "strict";

    let tested_method = &method(
        "file_ensure_key_value_option",
        &[file_str, key, value, separator, option],
    )
    .enforce();

    let r = MethodTestSuite::new()
        .given(Given::file_present(
            file_str,
            r#"
# File has multiple entries for the same key
key1=value1
key1=value2
"#,
        ))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Repaired]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Repaired);

    let file_contents = std::fs::read_to_string(file_str).unwrap();
    assert_eq!(
        file_contents,
        r#"
# File has multiple entries for the same key
key1=new_value
key1=new_value
"#,
        "The first occurrence of the key should have been updated while the second remains unchanged."
    );

    end_test(workdir);
}
