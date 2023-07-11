// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

//! Tests special cases compilation

use std::{
    fs::read_to_string,
    path::{Path, PathBuf},
};

use pretty_assertions::assert_eq;
use rudder_commons::{Target, ALL_TARGETS};
use rudderc::compiler::read_technique;
use rudderc::{
    action,
    compiler::{metadata, Methods},
    frontends::methods::read_methods,
};
use test_generator::test_resources;

const TEST_METHODS: &str = "tests/lib/common/30_generic_methods";

/// Compiles all files in `cases/general`. Files ending in `.fail.yml` are expected to fail.
#[test_resources("tests/cases/general/*/*.yml")]
fn compile(filename: &str) {
    let input = read_to_string(filename).unwrap();
    let methods = read_methods(&[PathBuf::from(TEST_METHODS)]).unwrap();
    let file = Path::new(filename);
    lint_file(file);
    compile_metadata(methods, &input, file);
    for target in ALL_TARGETS {
        compile_file(methods, &input, file, *target);
    }
}

/// Lint the given file
fn lint_file(source: &Path) {
    let result = action::check(&[PathBuf::from(TEST_METHODS)], source);
    if should_fail(source) {
        assert!(result.is_err());
    } else {
        result.expect("Test check failed");
    }
}

/// Compile the metadata.xml
fn compile_metadata(methods: &'static Methods, input: &str, source: &Path) {
    let result = read_technique(methods, input).and_then(|p| metadata(p, source));
    if should_fail(source) {
        assert!(result.is_err());
    } else {
        let output = result.expect("Test compilation failed");
        let ref_file = source.parent().unwrap().join("metadata.xml");
        // Update ref files
        //std::fs::write(&ref_file, &output).unwrap();

        let reference = read_to_string(ref_file).unwrap();
        assert_eq!(reference, output);
    }
}

/// Compile the given source file with the given target. Panics if compilation fails.
fn compile_file(methods: &'static Methods, input: &str, source: &Path, target: Target) {
    let result = read_technique(methods, input)
        .and_then(|p| rudderc::compiler::compile(p, target, source, false));
    if should_fail(source) {
        assert!(result.is_err());
    } else {
        let output = result.expect("Test compilation failed");
        let ref_file = source.with_extension(target.extension());
        // Update ref files
        //std::fs::write(&ref_file, &output).unwrap();

        let reference = read_to_string(ref_file).unwrap();
        assert_eq!(reference, output);
    }
}

/// Failing tests should end in `.fail.yml`
fn should_fail(source: &Path) -> bool {
    source
        .file_name()
        .unwrap()
        .to_string_lossy()
        .ends_with(".fail.yml")
}
