// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Normation SAS

use std::{fs, path::PathBuf};

use anyhow::{bail, Context};
use serde::{Deserialize, Serialize};
use serde_json::Value;

use rudder_resource_type::{
    parameters::Parameters, run, CheckApplyResult, Outcome, PolicyMode, ResourceType0,
    ResourceTypeMetadata, ValidateResult,
};

// Configuration

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, Copy)]
#[serde(rename_all = "snake_case")]
pub enum State {
    Present,
    Absent,
}

impl Default for State {
    fn default() -> Self {
        Self::Present
    }
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub struct DirectoryParameters {
    path: PathBuf,
    #[serde(default)]
    state: State,
}

// Resource

struct Directory {}

impl ResourceType0 for Directory {
    fn metadata(&self) -> ResourceTypeMetadata {
        let raw = include_str!("./resource_directory.yml");
        ResourceTypeMetadata::from_metadata(raw).expect("invalid metadata")
    }

    fn validate(&self, parameters: &Parameters) -> ValidateResult {
        // Parse as parameters type
        let _parameters: DirectoryParameters =
            serde_json::from_value(Value::Object(parameters.data.clone()))?;
        Ok(())
    }

    fn check_apply(&mut self, mode: PolicyMode, parameters: &Parameters) -> CheckApplyResult {
        let parameters: DirectoryParameters =
            serde_json::from_value(Value::Object(parameters.data.clone()))?;
        let directory = parameters.path.as_path();
        let dir = directory.display();

        let current_state = if directory.exists() {
            State::Present
        } else {
            State::Absent
        };

        let outcome = match (mode, parameters.state, current_state) {
            // Ok
            (_, e, c) if e == c => Outcome::success(),
            // Enforce
            (PolicyMode::Enforce, State::Present, State::Absent) => {
                fs::create_dir(directory).with_context(|| "Creating directory {dir}")?;
                Outcome::repaired("Created directory {dir}")
            }
            (PolicyMode::Enforce, State::Absent, State::Present) => {
                fs::remove_dir(directory).with_context(|| "Removing directory {dir}")?;
                Outcome::repaired("Removed directory {dir}")
            }
            // Audit
            (PolicyMode::Audit, State::Present, State::Absent) => {
                bail!("Directory {dir} should be present but is not")
            }
            (PolicyMode::Audit, State::Absent, State::Present) => {
                bail!("Directory {dir} should not be present but exists")
            }
            _ => unreachable!(),
        };
        Ok(outcome)
    }
}

// Start runner

fn main() -> Result<(), anyhow::Error> {
    let directory_promise_type = Directory {};
    // Run the promise executor
    run(directory_promise_type)
}
