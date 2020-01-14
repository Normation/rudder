// To test only the technique compiler, run this: `cargo test --test compile`

/// this file will be the integration tests base for techniques compilation to cfengine file
/// calling files from the *techniques* folder
/// takes an rl file and a cf file, parses the first, compiles, and compares expected output with the second
/// naming convention: 
/// input is rl -> state_checkdef.rl
/// output is .rl.cf -> state_checkdef.rl.cf
/// with success: s_ & failure: f_
/// example of files that should succeed: s_errors.rl s_errors.rl.cf
// TODO: cf files added (solely in case of success) for later use: comparing it to generated output (.rl.cf)

use std::{
    fs,
    io::Error,
    io::prelude::*,
    path::{Path, PathBuf},
    ffi::OsStr,
};
use colored::Colorize;

// This static array is an alternative (yet exactly the same as the toml file test).
// Trace of some iterative work, need to pick between toml file test and directly in a test file
static TESTS: &'static [(&str, bool, &str)] = &[
    (
        // a proper enum definition, should pass
        "s_purest.rl",
        true,
        r#"@format=0
        "#
    ),
    (
        // a wrong enum definition (enm keyword)
        "f_enm.rl",
        false,
        r#"@format=0
        enm error {
            ok,
            err
        }"#
    ),
    (
        // a wrong enum definition (enm keyword)
        "f_enum.rl",
        true,
        r#"@format=0
        enum error {
            ok,
            err
        }"#
    ),
];

#[test]
// Tests statically string-defined "files"
fn raw_filestrings() -> Result<(), Error> {
    fs::create_dir_all("tests/tmp")?;
    let tests: Vec<(&str, bool, &str)> = TESTS.iter().cloned().collect();
    for (filename, is_ok, content) in tests {
        let path = PathBuf::from(&format!("tests/tmp/{}", filename));
        prepare_temporary_file(&path, content)?;
        let result = test_file(&path);
        assert_eq!(result.is_ok(), is_ok);
        fs::remove_file(path).expect("Could not delete temporary file");
    }
    Ok(())
}

#[test]
// Tests statically string-defined "files"
fn literal_filestrings() -> Result<(), Error> {
    fs::create_dir_all("tests/tmp")?;
    match fs::read_to_string(Path::new("tests/virtual_files.toml")) {
        Ok(content) => {
            match content.parse::<toml::Value>() {
                Ok(toml_file) => test_toml(toml_file)?,
                Err(e) => return Err(e.into()),
            }
        },
        Err(e) => return Err(e),
    }
    // let tests: Vec<(&str, bool, &str)> = TESTS.iter().cloned().collect();
    Ok(())
}

fn test_toml(toml_file: toml::Value) -> Result<(), Error> {
    if let Some(files) = toml_file.as_table() {
        for (filename, content) in files {
            if let Some(content) = content.as_str() {
                let path = PathBuf::from(&format!("tests/tmp/{}", filename));
                prepare_temporary_file(&path, content)?;
                let result = test_file(&path);
                match should_compile(&path) {
                    Some(is_success) => assert_eq!(result.is_ok(), is_success),
                    None => println!("{}: could not test  {:?}", "Warning (test)".bright_yellow().bold(), path.to_str().unwrap().bright_yellow()),
                }
                fs::remove_file(path).expect("Could not delete temporary file");
            }
        }
    }
    Ok(())
}

#[test]
// Tests every file from the */compile* folder
fn real_files() -> Result<(), Error> {
    fs::create_dir_all("tests/tmp")?;
    let file_paths = fs::read_dir("tests/compile")?;
    for file_entry in file_paths {
        let path: &Path= &file_entry?.path();
        if path.extension() == Some(OsStr::new("rs")) {
            let result = test_file(path);
            match should_compile(path) {
                Some(is_success) => assert_eq!(result.is_ok(), is_success),
                None => println!("{}: could not test  {:?}", "Warning (test)".bright_yellow().bold(), path.to_str().unwrap().bright_yellow()),
            }
        }
    }
    Ok(())
}

/// Tool function extracting expected compilation result
fn should_compile(path: &Path) -> Option<bool> {
    if let Some(filename) = path.file_name() {
        return match &filename.to_str().unwrap()[ .. 2] {
            "s_" => Some(true),
            "f_" => Some(false),
            _ => None,
        }    
    }
    None
}

/// Tool function dedicated to create a temporary file, writing content in it.
/// Allows to test simulated files based on strings
fn prepare_temporary_file(path: &Path, content: &str) -> Result<(), Error> {
    let mut file = fs::File::create(path)?;
    file.write_all(content.as_bytes())?;
    Ok(())
}

/// Test technique compilation from base crate on a given file
fn test_file(path: &Path) -> Result<(), String> {
    let filename = path.to_str().unwrap();
    match rudderc::compile::compile_file(path, path, false) {
        Err(rudderc::error::Error::User(e)) => {
            println!("{}: compilation of {} failed: {}", "Error (test)".bright_red().bold(), filename.bright_yellow(), e);
            return Err(e)
        },
        Ok(_) => {
            println!("{}: compilation of {}", "Success (test)".bright_green().bold(), filename.bright_yellow());
            return Ok(())
        },
        _ => panic!("What kind of error is this ?"),
    };
}