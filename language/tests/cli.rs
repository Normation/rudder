// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

//! Tests the CLI from outside by calling the binary directly

use assert_cmd::Command;

#[test]
fn it_saves_a_technique_based_on_json() {
    let mut cmd = Command::cargo_bin("rudderc").unwrap();
    cmd.args([
        "save",
        "-c",
        "tools/rudderc-dev.conf",
        "-i",
        "tests/simple_technique/technique.json",
    ]);
    cmd.assert().success();
}

#[test]
fn it_recreates_a_json_based_on_rd() {
    let mut cmd = Command::cargo_bin("rudderc").unwrap();
    cmd.args([
        "technique",
        "read",
        "-c",
        "tools/rudderc-dev.conf",
        "-i",
        "../simple_technique/technique.rd",
    ]);
    cmd.assert().success();
}

#[test]
fn it_compiles_a_technique_into_cfengine() {
    let mut cmd = Command::cargo_bin("rudderc").unwrap();
    cmd.args([
        "compile",
        "-c",
        "tools/rudderc-dev.conf",
        "-i",
        "../simple_technique/technique.rd",
        "-o",
        "../simple_technique/technique.cf",
    ]);
    cmd.assert().success();
}

#[test]
fn it_compiles_a_technique_into_cfengine_verbosely() {
    let mut cmd = Command::cargo_bin("rudderc").unwrap();
    cmd.args([
        "compile",
        "-c",
        "tools/rudderc-dev.conf",
        "-i",
        "../simple_technique/technique.rd",
        "-f",
        "cf",
        "-l",
        "debug",
        "-j",
    ]);
    cmd.assert().success();
}

#[test]
fn it_compiles_a_technique_into_dsc() {
    let mut cmd = Command::cargo_bin("rudderc").unwrap();
    cmd.args([
        "compile",
        "-c",
        "tools/rudderc-dev.conf",
        "-i",
        "../simple_technique/technique.rd",
        "-f",
        "dsc",
    ]);
    cmd.assert().success();
}

#[test]
fn it_generates_rd_cf_dsc_as_bundled_json() {
    let mut cmd = Command::cargo_bin("rudderc").unwrap();
    cmd.args([
        "technique",
        "generate",
        "-c",
        "tools/rudderc-dev.conf",
        "-i",
        "tests/simple_technique/technique.json",
    ]);
    cmd.assert().success();
}
