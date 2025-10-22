use crate::integration::{end_test, get_lib_path, init_test};
use crate::testlib::given::Given;
use crate::testlib::method_test_suite::MethodTestSuite;
use crate::testlib::method_to_test::{MethodStatus, method};

#[test]
fn it_should_remove_exact_lines() {
    let workdir = init_test();
    let file_path = workdir.path().join("cleaned.txt");
    let file_str = file_path.to_str().unwrap();

    let original = r#"line1
debug=true
line3
debug=false
line5"#;

    let tested_method = &method("file_lines_absent", &[file_str, r#"^debug=.*"#]).enforce();

    let r = MethodTestSuite::new()
        .given(Given::file_present(file_str, original))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Repaired]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Repaired);

    let content = std::fs::read_to_string(file_str).unwrap();
    assert_eq!(
        content,
        r#"line1
line3
line5
"#,
        "File content after removing matching lines is incorrect"
    );

    end_test(workdir);
}

#[test]
fn it_should_do_nothing_if_lines_not_present() {
    let workdir = init_test();
    let file_path = workdir.path().join("unchanged.txt");
    let file_str = file_path.to_str().unwrap();

    let original = r#"server=example.com
port=443
mode=production"#;

    let tested_method = &method("file_lines_absent", &[file_str, r#"^debug=.*"#]).enforce();

    let r = MethodTestSuite::new()
        .given(Given::file_present(file_str, original))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Success]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Success);

    let content = std::fs::read_to_string(file_str).unwrap();
    assert_eq!(
        content, original,
        "File content should not be modified when no matching lines exist"
    );

    end_test(workdir);
}

#[test]
fn it_should_match_lines_using_regex() {
    let workdir = init_test();
    let file_path = workdir.path().join("regex_match.txt");
    let file_str = file_path.to_str().unwrap();

    let original = r#"# BEGIN CONFIG
temp=42
temp=99
final=true
# END CONFIG"#;

    let tested_method = &method("file_lines_absent", &[file_str, r#"^temp=.*"#]).enforce();

    let r = MethodTestSuite::new()
        .given(Given::file_present(file_str, original))
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Repaired]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Repaired);

    let content = std::fs::read_to_string(file_str).unwrap();
    assert_eq!(
        content,
        r#"# BEGIN CONFIG
final=true
# END CONFIG
"#,
        "Regex match should remove all lines starting with 'temp='"
    );

    end_test(workdir);
}

#[test]
fn it_should_succeeds_if_the_file_does_not_exist() {
    let workdir = init_test();
    let file_path = workdir.path().join("missing.txt");
    let file_str = file_path.to_str().unwrap();

    let tested_method = &method("file_lines_absent", &[file_str, r#"^.*$"#]).enforce();

    let r = MethodTestSuite::new()
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());

    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Success]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Success);

    end_test(workdir);
}
