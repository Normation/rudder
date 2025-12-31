// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS

mod cli;
mod db;
mod event;

use crate::event::Event;
use anyhow::{Context, bail};
use chrono::{DateTime, Utc};
use rudder_module_type::cfengine::called_from_agent;
use rudder_module_type::{
    CheckApplyResult, ModuleType0, ModuleTypeMetadata, PolicyMode, ValidateResult,
    parameters::Parameters, run_module,
};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::env;

const MODULE_NAME: &str = env!("CARGO_PKG_NAME");

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub struct SchedulerParameters {
    events: Vec<Event>,
}

struct SchedulerModule {}

impl SchedulerModule {
    pub fn new() -> Self {
        SchedulerModule {}
    }
}

impl ModuleType0 for SchedulerModule {
    fn metadata(&self) -> ModuleTypeMetadata {
        let meta = include_str!("../rudder_module_type.yml");
        let docs = include_str!("../README.md");
        ModuleTypeMetadata::from_metadata(meta)
            .expect("invalid metadata")
            .documentation(docs)
    }

    fn validate(&self, parameters: &Parameters) -> ValidateResult {
        let _parameters: SchedulerParameters =
            serde_json::from_value(Value::Object(parameters.data.clone()))?;
        Ok(())
    }

    fn check_apply(&mut self, mode: PolicyMode, parameters: &Parameters) -> CheckApplyResult {
        if mode != PolicyMode::Enforce {
            bail!("Scheduler module only supports enforce mode");
        }

        todo!()
    }
}

pub fn entry() -> anyhow::Result<(), anyhow::Error> {
    // SAFETY: The module is single-threaded.
    unsafe {
        env::set_var("LC_ALL", "C");
    }

    if called_from_agent() {
        run_module(SchedulerModule::new())
    } else {
        // The CLI does not use the module API
        //Cli::run()
        todo!()
    }
}
