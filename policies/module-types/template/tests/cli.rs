use assert_cmd::prelude::*;
use std::process::Command;
use tempfile::NamedTempFile;

#[test]
fn test_default_template_engine() -> Result<(), Box<dyn std::error::Error>> {
    // The default template engine is set to minijinja.
    let output_file = NamedTempFile::new()?;
    let mut cmd = Command::cargo_bin("rudder-module-template")?;

    cmd.arg("--template").arg("tests/template.j2");
    cmd.arg("--data").arg("tests/data.json");
    cmd.arg("--out").arg(output_file.path());
    cmd.assert().success();

    Ok(())
}

#[test]
fn test_minijinja_template_engine() -> Result<(), Box<dyn std::error::Error>> {
    let output_file = NamedTempFile::new()?;
    let mut cmd = Command::cargo_bin("rudder-module-template")?;

    cmd.arg("--template").arg("tests/template.j2");
    cmd.arg("--data").arg("tests/data.json");
    cmd.arg("--out").arg(output_file.path());
    cmd.arg("--engine").arg("minijinja");
    cmd.assert().success();

    Ok(())
}

#[test]
fn test_mustache_template_engine() -> Result<(), Box<dyn std::error::Error>> {
    let output_file = NamedTempFile::new()?;
    let mut cmd = Command::cargo_bin("rudder-module-template")?;

    cmd.arg("--template").arg("tests/template.mustache");
    cmd.arg("--data").arg("tests/data.json");
    cmd.arg("--out").arg(output_file.path());
    cmd.arg("--engine").arg("mustache");
    cmd.assert().success();

    Ok(())
}
