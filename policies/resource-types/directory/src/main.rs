// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText&: 2021 Normation SAS

use std::{
    fs,
    path::{Path, PathBuf},
};

use anyhow::{bail, Context, Error};
use serde::{Deserialize, Serialize};
use serde_json::Value;

use rudder_resource_type::{
    parameters::Parameters, run, CheckApplyResult, Outcome, PolicyMode, ResourceType0,
    ResourceTypeMetadata, StateResult, ValidateResult,
};

// Configuration

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize, Copy)]
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

impl TryFrom<&Path> for State {
    type Error = Error;

    fn try_from(value: &Path) -> Result<Self, Self::Error> {
        let res = if value.exists() {
            if value.is_dir() {
                State::Present
            } else {
                bail!(
                    "{} is not a directory but a {:?}",
                    value.display(),
                    value.metadata()?.file_type()
                )
            }
        } else {
            State::Absent
        };
        Ok(res)
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
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
        let meta = include_str!("../rudder_resource_type.yml");
        let docs = include_str!("../README.md");
        ResourceTypeMetadata::from_metadata(meta)
            .expect("invalid metadata")
            .documentation(docs)
    }

    fn validate(&self, parameters: &Parameters) -> ValidateResult {
        // Parse as parameters type
        let _parameters: DirectoryParameters =
            serde_json::from_value(Value::Object(parameters.data.clone()))?;
        Ok(())
    }

    fn check_apply(&mut self, mode: PolicyMode, parameters: &Parameters) -> CheckApplyResult {
        assert!(self.validate(parameters).is_ok());
        let parameters: DirectoryParameters =
            serde_json::from_value(Value::Object(parameters.data.clone()))?;
        let directory = parameters.path.as_path();
        let dir = directory.display();

        let current_state: State = directory.try_into()?;

        let outcome = match (mode, parameters.state, current_state) {
            // Ok
            (_, e, c) if e == c => Outcome::success(),
            // Enforce
            (PolicyMode::Enforce, State::Present, State::Absent) => {
                fs::create_dir(directory).with_context(|| "Creating directory {dir}")?;
                Outcome::repaired(format!("Created directory {dir}"))
            }
            (PolicyMode::Enforce, State::Absent, State::Present) => {
                fs::remove_dir(directory).with_context(|| "Removing directory {dir}")?;
                Outcome::repaired(format!("Removed directory {dir}"))
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

    fn state(&self, parameters: &Parameters) -> StateResult {
        assert!(self.validate(parameters).is_ok());
        let parameters: DirectoryParameters =
            serde_json::from_value(Value::Object(parameters.data.clone()))?;
        let directory = parameters.path.as_path();

        let current_state: State = directory.try_into()?;
        let value = serde_json::to_value(current_state)?;

        Ok(Some(value))
    }
}

// Start runner

fn main() -> Result<(), anyhow::Error> {
    let directory_promise_type = Directory {};
    // Run the promise executor
    run(directory_promise_type)
}
