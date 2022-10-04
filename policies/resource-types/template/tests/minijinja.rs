// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Normation SAS

use std::fs::read_to_string;
use std::{env, fs, path::Path};

use anyhow::anyhow;
use tempfile::tempdir;

use rudder_commons_test::resource_type::unix;
use rudder_resource_type::{Outcome, PolicyMode};

const BIN: &str = concat!("../../target/debug/", env!("CARGO_PKG_NAME"));

#[test]
fn it_renders_mini_jinja_inlined() {
    let root_dir = tempdir().unwrap();
    let test_path = root_dir.path().join("output");

    unix::test(
        Path::new(BIN),
        &format!(
            r#"{{"path": "{}", "engine": "{}", "template_src": "Hello {{{{ name }}}}!", "data": {{ "name": "ximou" }} }}"#,
            test_path.display(),
            "mini_jinja"
        ),
        PolicyMode::Enforce,
        Ok(Outcome::repaired("".to_string())),
    );
    let output = read_to_string(&test_path).unwrap();
    assert_eq!("Hello ximou!", output);
}

#[test]
fn it_renders_mini_jinja_from_file() {
    let root_dir = tempdir().unwrap();
    let test_path = root_dir.path().join("output");

    unix::test(
        Path::new(BIN),
        &format!(
            r#"{{"path": "{}", "engine": "{}", "template_path": "./tests/template.j2", "data": {{ "name": "you" }} }}"#,
            test_path.display(),
            "mini_jinja"
        ),
        PolicyMode::Enforce,
        Ok(Outcome::repaired("".to_string())),
    );
    let output = read_to_string(&test_path).unwrap();
    assert_eq!("Hello you!", output);
}

#[test]
fn it_checks_mini_jinja() {
    let root_dir = tempdir().unwrap();
    let test_path = root_dir.path().join("output");

    unix::test(
        Path::new(BIN),
        &format!(
            r#"{{"path": "{}", "engine": "{}", "template_src": "Hello {{{{ name }}}}!", "data": {{ "name": "ximou" }} }}"#,
            test_path.display(),
            "mini_jinja"
        ),
        PolicyMode::Audit,
        Err(anyhow!("")),
    );
    assert!(!test_path.exists());
}

#[test]
fn it_check_correct_mini_jinja() {
    let root_dir = tempdir().unwrap();
    let test_path = root_dir.path().join("output");
    fs::write(&test_path, "Hello World!").unwrap();

    unix::test(
        Path::new(BIN),
        &format!(
            r#"{{"path": "{}", "engine": "{}", "template_src": "Hello {{{{ name }}}}!", "data": {{ "name": "World" }} }}"#,
            test_path.display(),
            "mini_jinja"
        ),
        PolicyMode::Audit,
        Ok(Outcome::success()),
    );
    let output = read_to_string(&test_path).unwrap();
    assert_eq!("Hello World!", output);
}

#[test]
fn it_check_incorrect_mini_jinja() {
    let root_dir = tempdir().unwrap();
    let test_path = root_dir.path().join("output");
    fs::write(&test_path, "Hello!").unwrap();

    unix::test(
        Path::new(BIN),
        &format!(
            r#"{{"path": "{}", "engine": "{}", "template_src": "Hello {{{{ name }}}}!", "data": {{ "name": "World" }} }}"#,
            test_path.display(),
            "mini_jinja"
        ),
        PolicyMode::Audit,
        Ok(Outcome::success()),
    );
    let output = read_to_string(&test_path).unwrap();
    assert_eq!("Hello World!", output);
}
