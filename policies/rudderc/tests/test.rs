// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

//! Tests the testing framework
//!
//! Warning: As testing commands change working directory, these tests should not
//! start parallel jobs.

use std::{
    env,
    path::{Path, PathBuf},
};

use rudderc::action;
use test_generator::test_resources;

const UNIX_TEST_LIB: &str = "tests/lib/common";
// We need a real windows agent
const WINDOWS_TEST_LIB: &str = "../target/agent-windows/Rudder";
const TEST_METHODS: &str = "tests/lib/common/30_generic_methods";

/// Compile and tests all files in `cases/test`. This tests the testing feature itself.
#[cfg(unix)]
#[test_resources("tests/cases/test/*_unix/*.yml")]
fn test_unix(filename: &str) {
    let technique_dir = Path::new(filename).parent().unwrap();
    let cwd = env::current_dir().unwrap();
    let src = technique_dir.join("technique.yml");

    action::build(
        &[PathBuf::from(TEST_METHODS)],
        &src,
        &technique_dir.join("target"),
        true,
        false,
    )
    .unwrap();
    action::test(
        &src,
        &technique_dir.join("target"),
        &technique_dir.join("tests"),
        &[cwd.join(UNIX_TEST_LIB)],
        None,
        false,
    )
    .unwrap();
}

/// Compile and tests all files in `cases/test`. This tests the testing feature itself.
/// Even if tests for Windows, they only work on Linux for now
#[cfg(unix)]
#[test_resources("tests/cases/test/*_windows/*.yml")]
fn test_windows(filename: &str) {
    let technique_dir = Path::new(filename).parent().unwrap();
    let cwd = env::current_dir().unwrap();
    let src = technique_dir.join("technique.yml");

    action::build(
        &[PathBuf::from(TEST_METHODS)],
        &src,
        &technique_dir.join("target"),
        true,
        false,
    )
    .unwrap();
    let res = action::test(
        &src,
        &technique_dir.join("target"),
        &technique_dir.join("tests"),
        &[cwd.join(WINDOWS_TEST_LIB)],
        None,
        false,
    );
    dbg!(&res);
    res.unwrap();
}
