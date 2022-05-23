// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

//! Tests special cases compilation

// TODO: Add checks of compilation output!

use std::path::{Path, PathBuf};

use colored::Colorize;
use tempfile::tempdir;
use test_generator::test_resources;

use rudderc::{command::Command, generator::Format, io};

/// Compiles all files in `cases`. Files ending in `.fail.rd` are expected to fail.
#[test_resources("tests/cases/*/*.rd")]
fn compile(filename: &str) {
    compile_file(Path::new(filename), &Format::CFEngine);
    compile_file(Path::new(filename), &Format::DSC);
}

/// Compile the given source file with the given format. Panics if compilation fails.
fn compile_file(source: &Path, format: &Format) {
    let dir = tempdir().unwrap();
    let dest = dir.path().join("technique.rd");
    let result = call_compile(source, &dest, format);
    assert_eq!(
        result.is_ok(),
        should_succeed(source),
        "{}: {} assertion is not true. Generator result (lhs) differs from expectations (rhs)",
        "\nError (test)".bright_red().bold(),
        source.to_string_lossy().bright_yellow()
    );
}

/// Failing tests should end in `.fail.rd`
fn should_succeed(source: &Path) -> bool {
    !source
        .file_name()
        .unwrap()
        .to_string_lossy()
        .ends_with(".fail.rd")
}

/// Compile the source file
fn call_compile(source: &Path, dest: &Path, format: &Format) -> Result<(), String> {
    let (input, input_content) = io::get_content(&Some(source.to_path_buf()))
        .unwrap_or_else(|_| panic!("Could not get content from '{:?}'", source));
    let io = io::IOContext {
        stdlib: PathBuf::from("libs/"),
        input,
        input_content,
        output: Some(dest.to_path_buf()),
        command: Command::Compile,
        format: format.clone(),
    };
    match rudderc::command::compile(&io, true) {
        Ok(_) => {
            println!(
                "{}: compilation of {}",
                "Success (rudderc)".bright_green().bold(),
                source.to_string_lossy().bright_yellow()
            );
            Ok(())
        }
        Err(err) => {
            println!(
                "{}: compilation of {} failed:\n{}",
                "Error (rudderc)".bright_red().bold(),
                source.to_string_lossy().bright_yellow(),
                err
            );
            Err(err.to_string())
        }
    }
}
