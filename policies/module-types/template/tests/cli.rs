// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Normation SAS

use assert_cmd::prelude::*;
use std::io::Read;
use std::process::Command;
use tempfile::NamedTempFile;

static EXPECTED_FILE_CONTENT: &str = "Hello Ferris!";

#[test]
fn test_default_template_engine() -> Result<(), Box<dyn std::error::Error>> {
    // The default template engine is set to minijinja.
    let mut output_file = NamedTempFile::new()?;
    let mut cmd = Command::cargo_bin("rudder-module-template")?;

    cmd.arg("--template").arg("tests/template.j2");
    cmd.arg("--data").arg("tests/data.json");
    cmd.arg("--out").arg(output_file.path());
    cmd.assert().success();

    let mut file_content = String::new();
    output_file.read_to_string(&mut file_content)?;
    assert_eq!(EXPECTED_FILE_CONTENT, file_content);

    Ok(())
}

#[test]
fn test_minijinja_template_engine() -> Result<(), Box<dyn std::error::Error>> {
    let mut output_file = NamedTempFile::new()?;
    let mut cmd = Command::cargo_bin("rudder-module-template")?;

    cmd.arg("--template").arg("tests/template.j2");
    cmd.arg("--data").arg("tests/data.json");
    cmd.arg("--out").arg(output_file.path());
    cmd.arg("--engine").arg("minijinja");
    cmd.assert().success();

    let mut file_content = String::new();
    output_file.read_to_string(&mut file_content)?;
    assert_eq!(EXPECTED_FILE_CONTENT, file_content);

    Ok(())
}

#[test]
#[cfg(target_family = "unix")]
fn test_jinja2_template_engine() -> Result<(), Box<dyn std::error::Error>> {
    let mut output_file = NamedTempFile::new()?;
    let mut cmd = Command::cargo_bin("rudder-module-template")?;

    cmd.arg("--template").arg("tests/template.j2");
    cmd.arg("--data").arg("tests/data.json");
    cmd.arg("--out").arg(output_file.path());
    cmd.arg("--engine").arg("jinja2");
    cmd.assert().success();

    let mut file_content = String::new();
    output_file.read_to_string(&mut file_content)?;
    assert_eq!(EXPECTED_FILE_CONTENT, file_content);

    Ok(())
}

#[test]
fn test_mustache_template_engine() -> Result<(), Box<dyn std::error::Error>> {
    let mut output_file = NamedTempFile::new()?;
    let mut cmd = Command::cargo_bin("rudder-module-template")?;

    cmd.arg("--template").arg("tests/template.mustache");
    cmd.arg("--data").arg("tests/data.json");
    cmd.arg("--out").arg(output_file.path());
    cmd.arg("--engine").arg("mustache");
    cmd.assert().success();

    let mut file_content = String::new();
    output_file.read_to_string(&mut file_content)?;
    assert_eq!(EXPECTED_FILE_CONTENT, file_content);

    Ok(())
}

#[test]
fn test_mustache_cfengine() -> Result<(), Box<dyn std::error::Error>> {
    let mut output_file = NamedTempFile::new()?;
    let mut cmd = Command::cargo_bin("rudder-module-template")?;

    cmd.arg("--template").arg("tests/template2.mustache");
    cmd.arg("--data").arg("tests/data2.json");
    cmd.arg("--out").arg(output_file.path());
    cmd.arg("--engine").arg("mustache");
    cmd.assert().success();

    let mut file_content = String::new();
    output_file.read_to_string(&mut file_content)?;
    assert_eq!("[\"one\",\"two\"]", file_content);

    Ok(())
}
