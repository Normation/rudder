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

const TEST_LIB: &str = "tests/lib/common";
const TEST_METHODS: &str = "tests/lib/common/30_generic_methods";

/// Compile and tests all files in `cases/test`. This tests the testing feature itself.
#[test_resources("tests/cases/test/*/*.yml")]
fn test(filename: &str) {
    let technique_dir = Path::new(filename).parent().unwrap();
    let cwd = env::current_dir().unwrap();

    action::build(
        &[PathBuf::from(TEST_METHODS)],
        technique_dir.join("technique.yml").as_path(),
        technique_dir.join("target").as_path(),
        true,
        false,
    )
    .unwrap();
    action::test(
        technique_dir.join("target").join("technique.cf").as_path(),
        technique_dir.join("tests").as_path(),
        &[cwd.join(TEST_LIB)],
        None,
        false,
    )
    .unwrap();
}
