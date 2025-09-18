// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

//! Tests special cases compilation

use std::{
    fs::read_to_string,
    path::{Path, PathBuf},
    sync::Once,
};

use pretty_assertions::assert_eq;
use rudder_cli::logs;
use rudder_commons::{ALL_TARGETS, Target, methods::Methods};
use rudderc::{
    action,
    compiler::{metadata, read_technique},
    frontends::read_methods,
};
use test_generator::test_resources;

static INIT: Once = Once::new();

/// We can only initialize the logger once
pub fn init_logs() {
    INIT.call_once(|| {
        let _guard = logs::init(0, false, Default::default(), None);
    });
}

const TEST_METHODS: &str = "tests/lib/common/30_generic_methods";

/// Compiles all files in `cases/general`. Files ending in `.fail.yml` are expected to fail.
#[test_resources("tests/cases/general/*/technique.y*ml")]
fn compile(filename: &str) {
    init_logs();
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
    result.expect("Test check failed");
}

/// Compile the metadata.xml
fn compile_metadata(methods: &'static Methods, input: &str, source: &Path) {
    let result = read_technique(methods, input, true).and_then(|p| metadata(p, source));
    let output = result.expect("Test compilation failed");
    let ref_file = source.parent().unwrap().join("metadata.xml");
    // Update ref files
    //std::fs::write(&ref_file, &output).unwrap();

    let reference = read_to_string(ref_file).unwrap();
    assert_eq!(reference.trim(), output.trim());
}

/// Compile the given source file with the given target. Panics if compilation fails.
fn compile_file(methods: &'static Methods, input: &str, source: &Path, target: Target) {
    let result = read_technique(methods, input, true)
        .and_then(|p| rudderc::compiler::compile(p, target, source, false));

    let output = result.expect("Test compilation failed");
    let ref_file = source.with_extension(target.extension());
    // Update ref files
    std::fs::write(&ref_file, &output).unwrap();

    let reference = read_to_string(ref_file).unwrap();
    assert_eq!(reference.trim(), output.trim());
}
