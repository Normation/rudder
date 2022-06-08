// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

//! Tests special cases compilation

use std::{fs::read_to_string, path::Path};

use anyhow::Result;
use pretty_assertions::assert_eq;
use rudderc::frontends::ncf::method::Method;
use test_generator::test_resources;

/// Compiles all files in `cases`. Files ending in `.fail.yml` are expected to fail.
#[test_resources("tests/methods/*.yml")]
fn compile(filename: &str) {
    let path = Path::new(filename);
    let result: Result<Method> = read_to_string(path.with_extension("cf")).unwrap().parse();
    let reference: Method = serde_yaml::from_str(&read_to_string(path).unwrap()).unwrap();

    if should_fail(path) {
        assert!(result.is_err());
    } else {
        let parsed = result.unwrap();
        println!("{}", serde_yaml::to_string(&parsed).unwrap());
        assert_eq!(parsed, reference)
    }
}

/// Failing tests should end in `.fail.rd`
fn should_fail(source: &Path) -> bool {
    source
        .file_name()
        .unwrap()
        .to_string_lossy()
        .ends_with(".fail.yml")
}
