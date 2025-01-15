// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

//! Integration tests using CFEngine.

use std::{env, fs::read_to_string, path::Path};

use rudder_commons_test::module_type::unix;
use rudder_module_type::{Outcome, PolicyMode};
use tempfile::tempdir;

const BIN: &str = concat!("../../../target/debug/", env!("CARGO_PKG_NAME"));

#[test]
#[ignore]
fn it_renders_mustache_inlined() {
    let root_dir = tempdir().unwrap();
    let test_path = root_dir.path().join("output");

    unix::test(
        Path::new(BIN),
        &format!(
            r#"{{"path": "{}", "engine": "{}", "template_src": "Hello {{{{{{ name }}}}}}!", "data": {{ "name": "ximou" }} }}"#,
            test_path.display(),
            "mustache"
        ),
        PolicyMode::Enforce,
        Ok(Outcome::repaired("".to_string())),
    );
    let output = read_to_string(&test_path).unwrap();
    assert_eq!("Hello ximou!", output);
}
