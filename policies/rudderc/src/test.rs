// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

//! Technique test definition
//!
//! Techniques can contain tests cases in a `tests` folder.
//!
//! `Rudderc` reads all `.yml` files in the `tests` folders as test definitions.
//!
//! NOTE:
//! We try to stay close to the GitHub Actions and Gitlab CI logic and syntax when it makes sense.

// Test file specifications. Do we want several test cases in one file?

use anyhow::{Context, Result, bail};
use rudder_commons::{PolicyMode, Target, logs::ok_output};
use serde::{Deserialize, Serialize};
use std::{
    collections::{HashMap, HashSet},
    fs::{self, remove_file},
    path::Path,
    process::Command,
};

use crate::backends::windows::{POWERSHELL_BIN, POWERSHELL_OPTS};

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Step {
    #[serde(rename = "sh")]
    command: String,
}

impl Step {
    fn run(
        &self,
        target: Target,
        cwd: &Path,
        env: Vec<(&str, &str)>,
        target_dir: &Path,
    ) -> Result<()> {
        ok_output("Running", format!("'{}'", &self.command));
        let output = match target {
            Target::Unix => Command::new("/bin/sh")
                .arg("-c")
                .arg(&self.command)
                .envs(env.into_iter())
                .current_dir(cwd)
                .output()?,
            Target::Windows => {
                // Write the command into a script
                // To allow failing on error
                let script = target_dir.join("tmp.ps1");
                let _ = remove_file(&script);
                fs::write(&script, self.command.as_bytes())?;
                Command::new(POWERSHELL_BIN)
                    .args(POWERSHELL_OPTS)
                    .arg("-Command")
                    .arg(format!("&'{}'", &script.canonicalize()?.to_string_lossy()))
                    .envs(env.into_iter())
                    .current_dir(cwd)
                    .output()?
            }
        };
        if !output.status.success() {
            bail!(
                "Test '{}' failed\nstdout: {}\nstderr: {}",
                &self.command,
                String::from_utf8_lossy(&output.stdout),
                String::from_utf8_lossy(&output.stderr),
            )
        }
        Ok(())
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct TestCase {
    /// Target platform
    ///
    /// Note: It is the target in terms of backend, but Windows tests can run on Linux.
    #[serde(default)]
    pub target: Target,
    /// Parameters, we don't actually use them as they're loaded directly by the technique
    #[serde(rename = "params")]
    #[serde(default)]
    pub parameters: HashMap<String, String>,
    /// Conditions to define before running the test
    #[serde(default)]
    pub conditions: HashSet<String>,
    /// Policy mode used to run the technique
    #[serde(default)]
    pub policy_mode: PolicyMode,
    /// Test setup steps
    #[serde(default)]
    pub setup: Vec<Step>,
    /// Check test after
    pub check: Vec<Step>,
    /// Cleanup steps
    #[serde(default)]
    pub cleanup: Vec<Step>,
}

impl TestCase {
    pub fn setup(&self, dir: &Path, target_dir: &Path) -> Result<()> {
        for s in &self.setup {
            s.run(self.target, dir, vec![], target_dir)?;
        }
        Ok(())
    }

    pub fn check(&self, dir: &Path, reports_file: &Path, target_dir: &Path) -> Result<()> {
        let r = reports_file.canonicalize().unwrap();
        for s in &self.check {
            s.run(
                self.target,
                dir,
                vec![("REPORTS_FILE", &r.clone().to_string_lossy())],
                target_dir,
            )
            .with_context(|| format!("Reporting file: {}", r.to_string_lossy()))?;
        }
        Ok(())
    }

    pub fn cleanup(&self, dir: &Path, target_dir: &Path) -> Result<()> {
        for s in &self.cleanup {
            s.run(self.target, dir, vec![], target_dir)?;
        }
        Ok(())
    }
}
