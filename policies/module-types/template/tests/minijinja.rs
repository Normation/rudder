// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Normation SAS

use std::{
    env,
    fs::{self, read_to_string},
    path::Path,
};

use anyhow::anyhow;
use rudder_commons_test::module_type::unix;
use rudder_module_type::{Outcome, PolicyMode};
use tempfile::tempdir;

const BIN: &str = concat!("../../../target/debug/", env!("CARGO_PKG_NAME"));

#[test]
fn it_renders_minijinja_inlined() {
    let root_dir = tempdir().unwrap();
    let test_path = root_dir.path().join("output");

    unix::test(
        Path::new(BIN),
        &format!(
            r#"{{"path": "{}", "engine": "{}", "template_src": "Hello {{{{ name }}}}!", "data": {{ "name": "ximou" }} }}"#,
            test_path.display(),
            "minijinja"
        ),
        PolicyMode::Enforce,
        Ok(Outcome::repaired("".to_string())),
    );
    let output = read_to_string(&test_path).unwrap();
    assert_eq!("Hello ximou!", output);
}

#[test]
fn it_fails_on_undefined_values() {
    let root_dir = tempdir().unwrap();
    let test_path = root_dir.path().join("output");

    unix::test(
        Path::new(BIN),
        &format!(
            r#"{{"path": "{}", "engine": "{}", "template_src": "Hello {{{{ doesnotexist }}}}!", "data": {{ "name": "ximou" }} }}"#,
            test_path.display(),
            "minijinja"
        ),
        PolicyMode::Enforce,
        Err(anyhow!("")),
    );
    assert!(!test_path.exists());
}

#[test]
fn it_renders_minijinja_from_file() {
    let root_dir = tempdir().unwrap();
    let test_path = root_dir.path().join("output");

    unix::test(
        Path::new(BIN),
        &format!(
            r#"{{"path": "{}", "engine": "{}", "template_path": "./tests/template.j2", "data": {{ "name": "you" }} }}"#,
            test_path.display(),
            "minijinja"
        ),
        PolicyMode::Enforce,
        Ok(Outcome::repaired("".to_string())),
    );
    let output = read_to_string(&test_path).unwrap();
    assert_eq!("Hello you!", output);
}

#[test]
fn it_checks_minijinja() {
    let root_dir = tempdir().unwrap();
    let test_path = root_dir.path().join("output");

    unix::test(
        Path::new(BIN),
        &format!(
            r#"{{"path": "{}", "engine": "{}", "template_src": "Hello {{{{ name }}}}!", "data": {{ "name": "ximou" }} }}"#,
            test_path.display(),
            "minijinja"
        ),
        PolicyMode::Audit,
        Err(anyhow!("")),
    );
    assert!(!test_path.exists());
}

#[test]
fn it_checks_correct_minijinja() {
    let root_dir = tempdir().unwrap();
    let test_path = root_dir.path().join("output");
    fs::write(&test_path, "Hello World!").unwrap();

    unix::test(
        Path::new(BIN),
        &format!(
            r#"{{"path": "{}", "engine": "{}", "template_src": "Hello {{{{ name }}}}!", "data": {{ "name": "World" }} }}"#,
            test_path.display(),
            "minijinja"
        ),
        PolicyMode::Audit,
        Ok(Outcome::success()),
    );
    let output = read_to_string(&test_path).unwrap();
    assert_eq!("Hello World!", output);
}
