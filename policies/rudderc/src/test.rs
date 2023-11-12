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

use anyhow::{bail, Result};
use powershell_script::PsScriptBuilder;
use rudder_commons::{logs::ok_output, PolicyMode, Target};
use serde::{Deserialize, Serialize};
use std::{
    collections::{HashMap, HashSet},
    path::Path,
    process::Command,
};

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Step {
    #[serde(rename = "sh")]
    command: String,
}

impl Step {
    fn run(&self, target: Target, dir: &Path) -> Result<()> {
        ok_output("Running", format!("'{}'", &self.command));
        let output = match target {
            Target::Unix => Command::new("/bin/sh")
                .arg("-c")
                .arg(&self.command)
                .current_dir(dir)
                .output()?,
            Target::Windows => {
                let ps = PsScriptBuilder::new()
                    // load profile normally
                    .no_profile(false)
                    // non-interactive session
                    .non_interactive(true)
                    // don't show a window
                    .hidden(true)
                    // don't print commands in output
                    .print_commands(false)
                    .build();
                ps.run(&self.command)?.into_inner()
            }
        };
        if !output.status.success() {
            bail!(
                "Test '{}' failed\nstdout: {}\nstderr: {}",
                &self.command,
                String::from_utf8(output.stdout)?,
                String::from_utf8(output.stderr)?,
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
    setup: Vec<Step>,
    /// Check test after
    check: Vec<Step>,
    /// Cleanup steps
    #[serde(default)]
    cleanup: Vec<Step>,
}

impl TestCase {
    pub fn setup(&self, dir: &Path) -> Result<()> {
        for s in &self.setup {
            s.run(self.target, dir)?;
        }
        Ok(())
    }

    pub fn check(&self, dir: &Path) -> Result<()> {
        for s in &self.check {
            s.run(self.target, dir)?;
        }
        Ok(())
    }

    pub fn cleanup(&self, dir: &Path) -> Result<()> {
        for s in &self.cleanup {
            s.run(self.target, dir)?;
        }
        Ok(())
    }
}
