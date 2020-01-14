// Copyright 2019 Normation SAS
//
// This file is part of Rudder.
//
// Rudder is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// In accordance with the terms of section 7 (7. Additional Terms.) of
// the GNU General Public License version 3, the copyright holders add
// the following Additional permissions:
// Notwithstanding to the terms of section 5 (5. Conveying Modified Source
// Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
// Public License version 3, when you create a Related Module, this
// Related Module is not considered as a part of the work and may be
// distributed under the license agreement of your choice.
// A "Related Module" means a set of sources files including their
// documentation that, without modification of the Source Code, enables
// supplementary functions or services in addition to those offered by
// the Software.
//
// Rudder is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

use std::{
    fs,
    io::Write,
    path::{Path, PathBuf},
};
use colored::Colorize;

/// Paired with `test_case` proc-macro calls from the `compile.rs` test file.
/// Generates a file from a string and tests it
pub fn test_generated_file(filename: &str, content: &str) {
    fs::create_dir_all("tests/tmp").expect("Could not create /tmp dir");
    let path = PathBuf::from(format!("tests/tmp/{}.rl", filename));
    let mut file = fs::File::create(&path).expect("Could not create file");
    file.write_all(content.as_bytes()).expect("Could not write to file");
    test_file(&path, &path, filename);
    fs::remove_file(path).expect("Could not delete temporary file");
}

/// Paired with `test_case` proc-macro calls from the `compile.rs` test file.
/// Tests the file that matches the `filename` argument
pub fn test_real_file(filename: &str) {
    fs::create_dir_all("tests/tmp").expect("Could not create /tmp dir");
    let input_path = PathBuf::from(format!("tests/compile/{}.rl", filename));
    let output_path = PathBuf::from(format!("tests/target/{}.rl", filename));
    test_file(&input_path, &output_path, filename);
}

/// Core test function that actually compares the file compilation result to expected result
fn test_file(input_path: &Path, output_path: &Path, filename: &str) {
    let result = compile_file(&input_path, &output_path, filename);
    assert_eq!(
        result.is_ok(),
        should_compile(filename),
        "{}: {} assertion is not true. Compiler result (lhs) differs from expectations (rhs)",
        "Error (test)".bright_yellow().bold(),
        filename.bright_yellow(),
    );
}

/// Tool function extracting expected compilation result
fn should_compile(filename: &str) -> bool {
    return match &filename[ .. 2] {
        "s_" => true,
        "f_" => false,
        _ => panic!(
            "{}: file naming rules are not respected for {}, cannot assert test",
            "Warning (test)".bright_yellow().bold(),
            filename.bright_yellow(),
        )
    }
}

/// Compile technique from base crate and expose its result
fn compile_file(input_path: &Path, output_path: &Path, filename: &str) -> Result<(), String> {
    match rudderc::compile::compile_file(input_path, output_path, true) {
        Ok(_) => {
            println!("{}: compilation of {}", "Success (rudderc)".bright_green().bold(), filename.bright_yellow());
            Ok(())
        },
        Err(rudderc::error::Error::User(e)) => {
            println!("{}: compilation of {} failed: {}", "Error (rudderc)".bright_red().bold(), filename.bright_yellow(), e);
            Err(e)
        },
        _ => panic!("What kind of error is this ? (rudderc). Please report this bug"),
    }
}
