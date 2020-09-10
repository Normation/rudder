// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use rudderc::{Action, Format};

const RLFILES_PATH: &str = "tests/compile_techniques";
const TESTFILES_PATH: &str = "tests/tmp";

use colored::Colorize;
use std::{
    fs,
    io::Write,
    path::{Path, PathBuf},
};

/// Paired with `test_case` proc-macro calls from the `compile.rs` test file.
/// Generates a file from a string and tests it
pub fn test_generated_file(technique_name: &str, content: &str, format: &Format) {
    let dir = format!("{}/{}", TESTFILES_PATH, technique_name);
    fs::create_dir_all(&dir).unwrap_or_else(|_| panic!("Could not create {} dir", dir));
    let path = PathBuf::from(format!("{}/technique.rl", dir));
    let mut file = fs::File::create(&path).expect("Could not create file");
    file.write_all(content.as_bytes())
        .expect("Could not write to file");
    test_file(&path, &path, technique_name, format);
    fs::remove_dir_all(dir).expect("Could not delete temporary directory");
}

/// Paired with `test_case` proc-macro calls from the `compile.rs` test file.
/// Tests the file that matches the `technique_name` argument
pub fn test_real_file(technique_name: &str, format: &Format) {
    let dir = format!("{}/{}", TESTFILES_PATH, technique_name);
    fs::create_dir_all(&dir).unwrap_or_else(|_| panic!("Could not create {} dir", &dir));
    let source = PathBuf::from(format!("{}/{}.rl", RLFILES_PATH, technique_name));
    let dest = PathBuf::from(format!("{}/technique.rl", dir));
    test_file(&source, &dest, technique_name, format);
}

/// Core test function that actually compares the file compilation result to expected result
fn test_file(source: &Path, dest: &Path, technique_name: &str, format: &Format) {
    let result = compile_file(&source, &dest, technique_name, format);
    assert_eq!(
        result.is_ok(),
        should_compile(technique_name),
        "{}: {} assertion is not true. Generator result (lhs) differs from expectations (rhs)",
        "\nError (test)".bright_red().bold(),
        technique_name.bright_yellow(),
    );
}

/// Tool function extracting expected compilation result
fn should_compile(technique_name: &str) -> bool {
    match &technique_name[..2] {
        "s_" => true,
        "f_" => false,
        _ => panic!(
            "{}: file naming rules are not respected for {}, cannot assert test",
            "Warning (test)".bright_yellow().bold(),
            technique_name.bright_yellow(),
        ),
    }
}

/// Compile technique from base crate and expose its result
fn compile_file(
    source: &Path,
    dest: &Path,
    technique_name: &str,
    format: &Format,
) -> Result<(), String> {
    let io = rudderc::io::IOContext {
        stdlib: PathBuf::from("libs/"),
        source: source.to_path_buf(),
        dest: dest.to_path_buf(),
        action: Action::Compile,
        format: format.clone(),
    };
    match rudderc::compile::compile_file(&io, true) {
        Ok(_) => {
            println!(
                "{}: compilation of {}",
                "Success (rudderc)".bright_green().bold(),
                technique_name.bright_yellow()
            );
            Ok(())
        }
        Err(err) => {
            println!(
                "{}: compilation of {} failed:\n{}",
                "Error (rudderc)".bright_red().bold(),
                technique_name.bright_yellow(),
                err
            );
            Err(err.to_string())
        }
    }
}
