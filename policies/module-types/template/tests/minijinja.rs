// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Normation SAS

#[cfg(target_family = "unix")]
use std::{
    env,
    fs::{self, read_to_string},
    path::Path,
};

#[cfg(target_family = "unix")]
use anyhow::anyhow;
#[cfg(target_family = "unix")]
use rudder_commons_test::module_type::unix;
#[cfg(target_family = "unix")]
use rudder_module_type::{Outcome, PolicyMode};
#[cfg(target_family = "unix")]
use tempfile::tempdir;

#[cfg(target_family = "unix")]
const BIN: &str = concat!("../../../target/debug/", env!("CARGO_PKG_NAME"));

#[test]
#[cfg(target_family = "unix")]
fn it_renders_minijinja_inlined() {
    let root_dir = tempdir().unwrap();
    let test_path = root_dir.path().join("output");

    unix::test(
        Path::new(BIN),
        &format!(
            r#"{{"path": "{}", "engine": "{}", "template_path": "", "datastate_path": "", "template_src": "Hello {{{{ name }}}}!", "data": {{ "name": "ximou" }} }}"#,
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
#[cfg(target_family = "unix")]
fn it_fails_on_undefined_values() {
    let root_dir = tempdir().unwrap();
    let test_path = root_dir.path().join("output");

    unix::test(
        Path::new(BIN),
        &format!(
            r#"{{"path": "{}", "engine": "{}", "template_path": "", "datastate_path": "", "template_src": "Hello {{{{ doesnotexist }}}}!", "data": {{ "name": "ximou" }} }}"#,
            test_path.display(),
            "minijinja"
        ),
        PolicyMode::Enforce,
        Err(anyhow!("")),
    );
    assert!(!test_path.exists());
}

#[test]
#[cfg(target_family = "unix")]
fn it_renders_minijinja_from_file() {
    let root_dir = tempdir().unwrap();
    let test_path = root_dir.path().join("output");

    unix::test(
        Path::new(BIN),
        &format!(
            r#"{{"path": "{}", "engine": "{}", "template_src": "", "datastate_path": "", "template_path": "./tests/template.j2", "data": {{ "name": "you" }} }}"#,
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
#[cfg(target_family = "unix")]
fn it_checks_minijinja() {
    let root_dir = tempdir().unwrap();
    let test_path = root_dir.path().join("output");

    unix::test(
        Path::new(BIN),
        &format!(
            r#"{{"path": "{}", "engine": "{}", "template_path": "", "datastate_path": "", "template_src": "Hello {{{{ name }}}}!", "data": {{ "name": "ximou" }} }}"#,
            test_path.display(),
            "minijinja"
        ),
        PolicyMode::Audit,
        Err(anyhow!("")),
    );
    assert!(!test_path.exists());
}

#[test]
#[cfg(target_family = "unix")]
fn it_checks_correct_minijinja() {
    let root_dir = tempdir().unwrap();
    let test_path = root_dir.path().join("output");
    fs::write(&test_path, "Hello World!").unwrap();

    unix::test(
        Path::new(BIN),
        &format!(
            r#"{{"path": "{}", "engine": "{}", "template_path": "", "datastate_path": "", "template_src": "Hello {{{{ name }}}}!", "data": {{ "name": "World" }} }}"#,
            test_path.display(),
            "minijinja"
        ),
        PolicyMode::Audit,
        Ok(Outcome::success()),
    );
    let output = read_to_string(&test_path).unwrap();
    assert_eq!("Hello World!", output);
}
