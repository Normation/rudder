// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

//! Tests special cases compilation

use std::{
    fs::read_to_string,
    path::{Path, PathBuf},
};

use pretty_assertions::assert_eq;
use rudder_commons::Target;
use test_generator::test_resources;

/// Compiles all files in `cases`. Files ending in `.fail.yml` are expected to fail.
#[test_resources("tests/cases/*/*/*.yml")]
fn compile(filename: &str) {
    for t in [Target::Unix, Target::Windows, Target::Metadata] {
        compile_file(Path::new(filename), t);
    }
}

/// Compile the given source file with the given target. Panics if compilation fails.
fn compile_file(source: &Path, target: Target) {
    let result = rudderc::action::compile(&[PathBuf::from("tests/methods")], source, target);
    if should_fail(source) {
        assert!(result.is_err());
    } else {
        let output = result.expect("Test compilation failed");

        let dir = source.parent().unwrap();
        let ref_file = if target == Target::Metadata {
            dir.join("metadata.xml")
        } else {
            source.with_extension(target.extension())
        };
        // Update ref files
        //std::fs::write(&ref_file, &output).unwrap();

        let reference = read_to_string(ref_file).unwrap();

        assert_eq!(reference, output);
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
