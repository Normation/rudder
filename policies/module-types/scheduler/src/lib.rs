// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS

mod cli;
mod db;
mod event;

use crate::cli::Cli;
use crate::db::EventDatabase;
use crate::event::{Event, Events};
use anyhow::bail;
use chrono::{Duration, Utc};
use rudder_module_type::cfengine::called_from_agent;
use rudder_module_type::{
    CheckApplyResult, ModuleType0, ModuleTypeMetadata, Outcome, PolicyMode, ValidateResult,
    parameters::Parameters, rudder_debug, run_module,
};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::env;

const MODULE_NAME: &str = env!("CARGO_PKG_NAME");
const MODULE_FEATURES: [&str; 0] = [];

const RETENTION_DAYS: i64 = 60;

#[cfg(unix)]
pub const MODULE_DIR: &str = "/var/rudder/scheduler";
#[cfg(not(unix))]
pub const MODULE_DIR: &str = "C:/Program Files/rudder/scheduler";

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
        ModuleTypeMetadata::new(MODULE_NAME, Vec::from(MODULE_FEATURES))
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

        let p: SchedulerParameters =
            serde_json::from_value(Value::Object(parameters.data.clone()))?;

        let events: Events = p.into();

        let mut db = EventDatabase::new(Some(parameters.state_dir.as_path()))?;
        rudder_debug!("Cleaning events older than {} days", RETENTION_DAYS);
        db.clean(Duration::days(RETENTION_DAYS))?;

        let now = Utc::now();
        let classes = events.update(&mut db, now)?;

        Ok(if classes.is_empty() {
            Outcome::success()
        } else {
            Outcome::repaired_with(format!("Scheduled {} events", classes.len()), classes)
        })
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
        Cli::run()
    }
}
