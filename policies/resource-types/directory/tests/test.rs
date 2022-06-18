// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Normation SAS

use std::{env, fs, path::Path};

use anyhow::anyhow;
use tempfile::tempdir;

use rudder_commons_test::resource_type::unix;
use rudder_resource_type::{CheckApplyResult, Outcome, PolicyMode};

const BIN: &str = concat!("../../target/debug/", env!("CARGO_PKG_NAME"));

fn test(
    create_before: bool,
    state: &str,
    mode: PolicyMode,
    outcome: CheckApplyResult,
    exists_after: bool,
) {
    let root_dir = tempdir().unwrap();
    let test_dir = root_dir.path().join("test");
    if create_before {
        fs::create_dir(&test_dir).unwrap();
    }
    // Call the agent
    unix::test(
        Path::new(BIN),
        &format!(
            "{{\"path\": \"{}\", \"state\": \"{}\"}}",
            test_dir.display(),
            state
        ),
        mode,
        outcome,
    );
    if exists_after {
        assert!(test_dir.exists());
    } else {
        assert!(!test_dir.exists());
    }
}

#[test]
fn it_creates_missing_directory() {
    test(
        false,
        "present",
        PolicyMode::Enforce,
        Ok(Outcome::repaired("".to_string())),
        true,
    );
}

#[test]
fn it_does_not_create_dir_in_audit() {
    test(false, "present", PolicyMode::Audit, Err(anyhow!("")), false);
}

#[test]
fn it_removes_directory() {
    test(
        true,
        "absent",
        PolicyMode::Enforce,
        Ok(Outcome::repaired("".to_string())),
        false,
    );
}

#[test]
fn it_does_not_remove_dir_in_audit() {
    test(true, "absent", PolicyMode::Audit, Err(anyhow!("")), true);
}

#[test]
fn it_checks_absent_directory() {
    test(
        false,
        "absent",
        PolicyMode::Audit,
        Ok(Outcome::success()),
        false,
    );
}

#[test]
fn it_checks_present_directory() {
    test(
        true,
        "present",
        PolicyMode::Enforce,
        Ok(Outcome::success()),
        true,
    );
}
