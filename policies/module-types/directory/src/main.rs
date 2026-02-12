// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Normation SAS

use std::{fs, path::PathBuf};

use anyhow::{Context, bail};
use file_owner::PathExt;
use rudder_module_type::cfengine::called_from_agent;
use rudder_module_type::{
    CheckApplyResult, ModuleType0, ModuleTypeMetadata, Outcome, PolicyMode, ValidateResult,
    parameters::Parameters, run_module,
};
use serde::{Deserialize, Serialize};
use serde_json::Value;

pub const MODULE_NAME: &str = env!("CARGO_PKG_NAME");
pub const MODULE_FEATURES: [&str; 0] = [];

// Configuration

#[derive(Default, Clone, Debug, PartialEq, Eq, Serialize, Deserialize, Copy)]
#[serde(rename_all = "snake_case")]
pub enum State {
    #[default]
    Present,
    Absent,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub struct DirectoryParameters {
    /// Required parameter
    path: PathBuf,
    #[serde(default)]
    state: State,
    owner: Option<String>,
    /// Allowed owners for audit mode
    #[serde(default)]
    allowed_owners: Vec<String>,
    group: Option<String>,
    #[serde(default)]
    allowed_groups: Vec<String>,
    mode: Option<String>,
}

// Module

struct Directory {}

impl ModuleType0 for Directory {
    fn metadata(&self) -> ModuleTypeMetadata {
        ModuleTypeMetadata::new(MODULE_NAME, Vec::from(MODULE_FEATURES))
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

        let current_state = if directory.exists() {
            State::Present
        } else {
            State::Absent
        };
        let _current_owner = if directory.exists() {
            Some(directory.owner().unwrap())
        } else {
            None
        };
        let _current_group = if directory.exists() {
            Some(directory.group().unwrap())
        } else {
            None
        };

        let presence_outcome = match (mode, parameters.state, current_state) {
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
        Ok(presence_outcome)
    }
}

fn main() -> anyhow::Result<(), anyhow::Error> {
    let promise_type = Directory {};

    if called_from_agent() {
        run_module(promise_type)
    } else {
        unimplemented!("Only CFEngine mode is supported")
    }
}
