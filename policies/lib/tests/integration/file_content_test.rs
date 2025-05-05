// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS

use crate::integration::{end_test, get_lib_path, init_test};
use crate::testlib::method_test_suite::MethodTestSuite;
use crate::testlib::method_to_test::{MethodStatus, method};
use std::fs;

#[test]
fn it_writes_content_to_file() {
    let workdir = init_test();
    let file = workdir.path().join("file_to_edit");
    let file_path = &file.clone().to_string_lossy().into_owned();

    let tested_method = &method("file_content", &[file_path, "toto", "false"]).enforce();
    let r = MethodTestSuite::new()
        .when(tested_method)
        .execute(get_lib_path(), workdir.path().to_path_buf());
    r.assert_legacy_result_conditions(tested_method, vec![MethodStatus::Repaired]);
    r.assert_log_v4_result_conditions(tested_method, MethodStatus::Repaired);

    assert_eq!(
        "toto",
        fs::read_to_string(file).unwrap().trim_end(),
        "File content does not match the expected value"
    );

    end_test(workdir);
}
