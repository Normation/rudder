use crate::integration::{end_test, get_lib_path, init_test};
use crate::testlib::given::Given;
use crate::testlib::method_test_suite::MethodTestSuite;
use crate::testlib::method_to_test::{MethodStatus, method};

#[test]
fn it_should_succeed_if_section_and_block_are_correct_in_audit_mode() {
    let workdir = init_test();
    let file_path = workdir.path().join("test_file.txt");
    let file_str = file_path.to_str().unwrap();

    let section_start = "[SECTION_START]";
    let section_end = "[SECTION_END]";
    let block = "This is the correct block content";

    let tested_method = &method(
        "file_block_present_in_section",
        &[file_str, section_start, section_end, block],
    )
    .audit();

    let r = MethodTestSuite::new()
        .given(Given::file_present(
            file_str,
            r#"[SECTION_START]
This is the correct block content
[SECTION_END]"#,
        ))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Success]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Success);

    let file_contents = std::fs::read_to_string(file_str).unwrap();
    assert_eq!(
        file_contents,
        r#"[SECTION_START]
This is the correct block content
[SECTION_END]"#,
        "The file content is incorrect or misplaced."
    );

    end_test(workdir);
}

#[test]
fn it_should_return_error_if_section_missing_in_audit_mode() {
    let workdir = init_test();
    let file_path = workdir.path().join("test_file.txt");
    let file_str = file_path.to_str().unwrap();

    let section_start = "[SECTION_START]";
    let section_end = "[SECTION_END]";
    let block = "This is the block content";

    let tested_method = &method(
        "file_block_present_in_section",
        &[file_str, section_start, section_end, block],
    )
    .audit();

    let r = MethodTestSuite::new()
        .given(Given::file_present(file_str, ""))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Error]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Error);

    let file_contents = std::fs::read_to_string(file_str).unwrap();
    assert_eq!(
        file_contents, "",
        "The file content should be empty as the section is missing."
    );

    end_test(workdir);
}

#[test]
fn it_should_return_error_if_section_exists_but_block_is_incorrect_in_audit_mode() {
    let workdir = init_test();
    let file_path = workdir.path().join("test_file.txt");
    let file_str = file_path.to_str().unwrap();

    let section_start = "[SECTION_START]";
    let section_end = "[SECTION_END]";
    let block = "This is the correct block content";

    let tested_method = &method(
        "file_block_present_in_section",
        &[file_str, section_start, section_end, block],
    )
    .audit();

    let r = MethodTestSuite::new()
        .given(Given::file_present(
            file_str,
            r#"[SECTION_START]
This block content is wrong
[SECTION_END]"#,
        ))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Error]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Error);

    let file_contents = std::fs::read_to_string(file_str).unwrap();
    assert_eq!(
        file_contents,
        r#"[SECTION_START]
This block content is wrong
[SECTION_END]"#,
        "The file content was not as expected after the method execution."
    );

    end_test(workdir);
}

#[test]
fn it_should_repair_if_section_is_missing_in_enforce_mode() {
    let workdir = init_test();
    let file_path = workdir.path().join("test_file.txt");
    let file_str = file_path.to_str().unwrap();

    let section_start = "[SECTION_START]";
    let section_end = "[SECTION_END]";
    let block = "This is the block content";

    let tested_method = &method(
        "file_block_present_in_section",
        &[file_str, section_start, section_end, block],
    )
    .enforce();

    let r = MethodTestSuite::new()
        .given(Given::file_present(file_str, ""))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Repaired]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Repaired);

    let file_contents = std::fs::read_to_string(file_str).unwrap();
    assert_eq!(
        file_contents,
        r#"[SECTION_START]
This is the block content
[SECTION_END]
"#,
        "The section and block content were not added correctly."
    );

    end_test(workdir);
}

#[test]
fn it_should_repair_if_block_is_incorrect_in_enforce_mode() {
    let workdir = init_test();
    let file_path = workdir.path().join("test_file.txt");
    let file_str = file_path.to_str().unwrap();

    let section_start = "[SECTION_START]";
    let section_end = "[SECTION_END]";
    let block = "This is the correct block content";

    let tested_method = &method(
        "file_block_present_in_section",
        &[file_str, section_start, section_end, block],
    )
    .enforce();

    let r = MethodTestSuite::new()
        .given(Given::file_present(
            file_str,
            r#"[SECTION_START]
This block content is wrong
[SECTION_END]"#,
        ))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Repaired]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Repaired);

    let file_contents = std::fs::read_to_string(file_str).unwrap();
    assert_eq!(
        file_contents,
        r#"[SECTION_START]
This is the correct block content
[SECTION_END]
"#,
        "The block content was not repaired correctly."
    );

    end_test(workdir);
}
