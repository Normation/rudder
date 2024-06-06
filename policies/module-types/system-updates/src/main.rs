// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Normation SAS

#![allow(dead_code)]
#![allow(unused_variables)]
#![allow(unused_imports)]

mod cache;
mod package_manager;

use std::{fs, path::PathBuf};

use anyhow::{bail, Context};
use rudder_module_type::{
    parameters::Parameters, run, CheckApplyResult, ModuleType0, ModuleTypeMetadata, Outcome,
    PolicyMode, ValidateResult,
};
use serde::{Deserialize, Serialize};
use serde_json::Value;

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

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize, Copy)]
#[serde(rename_all = "snake_case")]
pub enum Provider {
    Default,
    Yum,
    Zypper,
    Apt,
}

impl Default for Provider {
    fn default() -> Self {
        Self::Default
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub struct PackageParameters {
    /// Required parameter
    name: String,
    #[serde(default)]
    state: State,
    version: Option<String>,
    architecture: Option<String>,
    provider: Provider,
    options: Option<String>,
}

// Module

struct Package {}

impl ModuleType0 for Package {
    fn metadata(&self) -> ModuleTypeMetadata {
        let meta = include_str!("../rudder_module_type.yml");
        let docs = include_str!("../README.md");
        ModuleTypeMetadata::from_metadata(meta)
            .expect("invalid metadata")
            .documentation(docs)
    }

    fn validate(&self, parameters: &Parameters) -> ValidateResult {
        // Parse as parameters type
        let _parameters: PackageParameters =
            serde_json::from_value(Value::Object(parameters.data.clone()))?;
        Ok(())
    }

    fn check_apply(&mut self, mode: PolicyMode, parameters: &Parameters) -> CheckApplyResult {
        assert!(self.validate(parameters).is_ok());
        let parameters: PackageParameters =
            serde_json::from_value(Value::Object(parameters.data.clone()))?;
        Ok(Outcome::Success(None))
    }
}

// Start runner

fn main() -> Result<(), anyhow::Error> {
    let package_promise_type = Package {};
    // Run the promise executor
    run(package_promise_type)
}
