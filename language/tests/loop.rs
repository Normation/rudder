// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

//! Calls the testing loop for all defined techniques

use std::path::Path;

use assert_cmd::Command;
use tempfile::tempdir;
use test_generator::test_resources;

const CFG: &str = "tools/rudderc-dev.conf";

/// Compiles all files in `techniques`.
#[test_resources("tests/techniques/*/*.json")]
fn test_technique(file: &str) {
    let tmp = tempdir().unwrap();

    /////////////////
    // generate files from json source
    /////////////////
    // Make sure we start from a clean state
    let mut cmd = Command::cargo_bin("rudderc").unwrap();
    cmd.args([
        "save",
        "--config-file",
        CFG,
        "--input",
        file,
        "--output",
        &tmp.path().join("technique.rd").to_string_lossy(),
    ]);
    cmd.assert().success();

    /////////////////
    // rl -> json
    /////////////////
    let mut cmd = Command::cargo_bin("rudderc").unwrap();
    cmd.args([
        "technique",
        "read",
        "--config-file",
        CFG,
        "--input",
        &tmp.path().join("technique.rd").to_string_lossy(),
        "--output",
        &tmp.path().join("technique.rd.json").to_string_lossy(),
    ]);
    cmd.assert().success();
    // compare json
    let mut cmd = Command::new("tools/generated_formats_tester");
    cmd.args([
        "compare-json",
        "--config-file",
        CFG,
        file,
        &tmp.path().join("technique.rd.json").to_string_lossy(),
    ]);
    cmd.assert().success();

    /////////////////
    // rl -> cf
    /////////////////
    let mut cmd = Command::cargo_bin("rudderc").unwrap();
    cmd.args([
        "compile",
        "--config-file",
        CFG,
        "--format",
        "cf",
        "--input",
        &tmp.path().join("technique.rd").to_string_lossy(),
        "--output",
        &tmp.path().join("technique.rd.cf").to_string_lossy(),
    ]);
    cmd.assert().success();
    // compare cf
    let mut cmd = Command::new("tools/generated_formats_tester");
    cmd.args([
        "compare-cf",
        "--config-file",
        CFG,
        &Path::new(file).with_extension("cf").to_string_lossy(),
        &tmp.path().join("technique.rd.cf").to_string_lossy(),
    ]);
    cmd.assert().success();

    /////////////////
    // rl -> dsc
    /////////////////
    let mut cmd = Command::cargo_bin("rudderc").unwrap();
    cmd.args([
        "compile",
        "--config-file",
        CFG,
        "--format",
        "dsc",
        "--input",
        &tmp.path().join("technique.rd").to_string_lossy(),
        "--output",
        &tmp.path().join("technique.rd.ps1").to_string_lossy(),
    ]);
    cmd.assert().success();
    // compare dsc
    let mut cmd = Command::new("tools/generated_formats_tester");
    cmd.args([
        "compare-dsc",
        "--config-file",
        CFG,
        &Path::new(file).with_extension("ps1").to_string_lossy(),
        &tmp.path().join("technique.rd.ps1").to_string_lossy(),
    ]);
    cmd.assert().success();
}
