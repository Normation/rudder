// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

#![allow(dead_code)]
#![allow(unused_variables)]
#![allow(unused_imports)]

mod actions;
mod campaign;
mod db;
mod hooks;
mod output;
mod package_manager;
mod scheduler;

use std::{env, path::PathBuf};

use crate::package_manager::PackageManager;
use anyhow::Context;
use chrono::{DateTime, Utc};
use package_manager::PackageSpec;
use rudder_module_type::{
    parameters::Parameters, run, CheckApplyResult, ModuleType0, ModuleTypeMetadata, Outcome,
    PolicyMode, ProtocolResult, ValidateResult,
};
use serde::{Deserialize, Serialize};
use serde_json::Value;

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize, Copy)]
#[serde(rename_all = "snake_case")]
pub enum RebootType {
    #[serde(alias = "enabled")]
    Always,
    AsNeeded,
    ServicesOnly,
    Disabled,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize, Copy, Default)]
#[serde(rename_all = "snake_case")]
pub enum CampaignType {
    #[default]
    System,
    Software,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub struct PackageParameters {
    #[serde(default)]
    state: CampaignType,
    provider: PackageManager,
    campaign_id: String,
    start: DateTime<Utc>,
    end: DateTime<Utc>,
    package_list: Vec<PackageSpec>,
    report_file: PathBuf,
}

// Module

// Un seul?

struct SystemUpdate {}

impl ModuleType0 for SystemUpdate {
    fn metadata(&self) -> ModuleTypeMetadata {
        let meta = include_str!("../rudder_module_type.yml");
        let docs = include_str!("../README.md");
        ModuleTypeMetadata::from_metadata(meta)
            .expect("invalid metadata")
            .documentation(docs)
    }

    fn init(&mut self) -> ProtocolResult {
        // FIXME: In the lib?
        env::set_var("LC_ALL", "C");
        ProtocolResult::Success
    }

    fn validate(&self, parameters: &Parameters) -> ValidateResult {
        // Parse as parameter types
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
    let package_promise_type = SystemUpdate {};
    // Run the promise executor
    run(package_promise_type)
}
