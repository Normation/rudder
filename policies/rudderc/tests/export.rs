// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use std::path::{Path, PathBuf};
use test_generator::test_resources;

use rudderc::action;

#[test_resources("tests/cases/general/*/technique.yml")]
fn compile(filename: &str) {
    // One example with resource, one example without
    let technique_dir = Path::new(filename).parent().unwrap();
    action::build(
        &[PathBuf::from("tests/lib/common")],
        &technique_dir.join("technique.yml"),
        &technique_dir.join("target"),
        false,
        true,
    )
    .unwrap();
    // Check that the generated technique is correct
    action::build(
        &[PathBuf::from("tests/lib/common")],
        &technique_dir.join("technique.ids.yml"),
        &technique_dir.join("target"),
        false,
        false,
    )
    .unwrap();
    let res = action::export(technique_dir, technique_dir.join("target"));
    res.unwrap();
}
