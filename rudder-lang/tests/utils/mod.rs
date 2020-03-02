// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

const RLFILES_PATH: &'static str = "tests/test_files/source_rl";
const TESTFILES_PATH: &'static str = "tests/test_files/tmp";

use colored::Colorize;
use std::{
    fs,
    io::Write,
    path::{Path, PathBuf},
};

/// Paired with `test_case` proc-macro calls from the `compile.rs` test file.
/// Generates a file from a string and tests it
pub fn test_generated_file(technique_name: &str, content: &str) {
    let dir = format!("{}/{}", TESTFILES_PATH, technique_name);
    fs::create_dir_all(&dir).expect(&format!("Could not create {} dir", dir));
    let path = PathBuf::from(format!("{}/technique.rl", dir));
    let mut file = fs::File::create(&path).expect("Could not create file");
    file.write_all(content.as_bytes())
        .expect("Could not write to file");
    test_file(&path, &path, technique_name);
    fs::remove_dir_all(dir).expect("Could not delete temporary directory");
}

/// Paired with `test_case` proc-macro calls from the `compile.rs` test file.
/// Tests the file that matches the `technique_name` argument
pub fn test_real_file(technique_name: &str) {
    let dir = format!("{}/real/{}", TESTFILES_PATH, technique_name);
    fs::create_dir_all(&dir).expect(&format!("Could not create {} dir", &dir));
    let input_path = PathBuf::from(format!("{}/{}.rl", RLFILES_PATH, technique_name));
    let output_path = PathBuf::from(format!("{}/technique.rl", dir));
    test_file(&input_path, &output_path, technique_name);
}

/// Core test function that actually compares the file compilation result to expected result
fn test_file(input_path: &Path, output_path: &Path, technique_name: &str) {
    let result = compile_file(&input_path, &output_path, technique_name);
    assert_eq!(
        result.is_ok(),
        should_compile(technique_name),
        "{}: {} assertion is not true. Compiler result (lhs) differs from expectations (rhs)",
        "\nError (test)".bright_red().bold(),
        technique_name.bright_yellow(),
    );
}

/// Tool function extracting expected compilation result
fn should_compile(technique_name: &str) -> bool {
    return match &technique_name[..2] {
        "s_" => true,
        "f_" => false,
        _ => panic!(
            "{}: file naming rules are not respected for {}, cannot assert test",
            "Warning (test)".bright_yellow().bold(),
            technique_name.bright_yellow(),
        ),
    };
}

/// Compile technique from base crate and expose its result
fn compile_file(input_path: &Path, output_path: &Path, technique_name: &str) -> Result<(), String> {
    match rudderc::compile::compile_file(
        input_path,
        output_path,
        true,
        &PathBuf::from("libs/")
    ) {
        Ok(_) => {
            println!(
                "{}: compilation of {}",
                "Success (rudderc)".bright_green().bold(),
                technique_name.bright_yellow()
            );
            Ok(())
        }
        Err(rudderc::error::Error::User(e)) => {
            println!(
                "{}: compilation of {} failed: {}",
                "Error (rudderc)".bright_red().bold(),
                technique_name.bright_yellow(),
                e
            );
            Err(e)
        },
        Err(rudderc::error::Error::List(e)) => {
            println!(
                "{}: compilation of {} failed: {:#?}",
                "Error (rudderc)".bright_red().bold(),
                technique_name.bright_yellow(),
                e
            );
            Err(e.join("\n"))
        }
    }
}
