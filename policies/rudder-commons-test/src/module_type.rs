// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Normation SAS

pub mod unix {
    use std::{fs, path::Path};

    use assert_cmd::{Command, prelude::*};
    use predicates::prelude::*;
    use rudder_module_type::{CheckApplyResult, Outcome, PolicyMode};
    use tempfile::tempdir;

    const CF_BIN_DIR: &str = "/opt/rudder/bin/";

    // TODO: Test loop with source yaml and rudderc
    //let mut cmd = Command::cargo_bin("rudderc").unwrap();

    /// Test given module type with CFEngine
    pub fn test(bin: &Path, data: &str, policy_mode: PolicyMode, outcome: CheckApplyResult) {
        // Use a dedicated workdir for each test
        // Required to run agents concurrently without trouble
        let work_dir = tempdir().unwrap();

        // Prepare promises.cf from source template
        let policy_src = include_str!("./test.cf");
        let policy = policy_src.replace("BINARY", &bin.to_string_lossy());
        let policy_path = work_dir.path().join("inputs").join("promises.cf");

        let cfe_dir = Path::new(CF_BIN_DIR);
        // put cf-promises into workdir/bin
        let bin_dir = work_dir.path().join("bin");
        fs::create_dir(&bin_dir).unwrap();
        fs::copy(cfe_dir.join("cf-promises"), bin_dir.join("cf-promises")).unwrap();
        // write test policy into promises.cf
        fs::create_dir(work_dir.path().join("inputs")).unwrap();
        fs::write(policy_path, policy.as_bytes()).unwrap();

        let action_policy = match policy_mode {
            PolicyMode::Enforce => "fix",
            PolicyMode::Audit => "warn",
        };
        let cmd = Command::new(cfe_dir.join("cf-agent"))
            .args(["--no-lock", "--workdir", &work_dir.path().to_string_lossy()])
            .env("TEST_DATA", data)
            .env("TEST_ACTION_POLICY", action_policy)
            .unwrap();

        let cfe_outcome = match outcome {
            Ok(Outcome::Success(_)) => "kept",
            Ok(Outcome::Repaired(_, _)) => "repaired",
            Err(_) => "error",
        };
        cmd.assert()
            .success()
            .stdout(predicate::str::contains(format!("R: TEST={cfe_outcome}\n")));
    }
}
