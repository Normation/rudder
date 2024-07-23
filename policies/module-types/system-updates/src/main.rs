// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

#![allow(dead_code)]
#![allow(unused_variables)]
#![allow(unused_imports)]

mod campaign;
mod db;
mod hooks;
mod output;
mod package_manager;
mod scheduler;
mod system;

use crate::{campaign::check_update, package_manager::PackageManager};
use anyhow::Context;
use chrono::{DateTime, Duration, RoundingError::DurationExceedsTimestamp, Utc};
use package_manager::PackageSpec;
use rudder_module_type::cfengine::CFENGINE_MODE_ARG;
use rudder_module_type::{
    parameters::Parameters, run, CheckApplyResult, ModuleType0, ModuleTypeMetadata, Outcome,
    PolicyMode, ProtocolResult, ValidateResult,
};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::process::exit;
use std::{env, fs, path::PathBuf};

// Same as the python implementation
pub const MODULE_DIR: &str = "/var/rudder/system-update";

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
    /// Install all available updates
    System,
    /// Install all security upgrades
    Security,
    /// Install the updates from the provided package list
    Software,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub struct PackageParameters {
    #[serde(default)]
    campaign_type: CampaignType,
    /// Rely on the agent to detect the OS and chose the right package manager.
    /// Avoid multiplying the amount of environment detection sources.
    package_manager: PackageManager,
    event_id: String,
    campaign_name: String,
    reboot_type: RebootType,
    start: DateTime<Utc>,
    end: DateTime<Utc>,
    package_list: Vec<PackageSpec>,
    report_file: PathBuf,
    schedule_file: PathBuf,
}

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
            serde_json::from_value(Value::Object(parameters.data.clone()))
                .context("Parsing module parameters")?;
        Ok(())
    }

    fn check_apply(&mut self, mode: PolicyMode, parameters: &Parameters) -> CheckApplyResult {
        //assert!(self.validate(parameters).is_ok());
        let package_parameters: PackageParameters =
            serde_json::from_value(Value::Object(parameters.data.clone()))
                .context("Parsing module parameters")?;
        let agent_freq = Duration::minutes(parameters.agent_frequency_minutes as i64);
        check_update(
            &parameters.node_id,
            parameters.state_dir.as_path(),
            agent_freq,
            package_parameters,
        )
    }
}

// Start runner

fn main() -> Result<(), anyhow::Error> {
    let mut package_promise_type = SystemUpdate {};

    let args: Vec<String> = env::args().collect();
    dbg!(&args);

    // Run the promise executor
    if args.contains(&CFENGINE_MODE_ARG.to_string()) {
        run(package_promise_type)
    } else {
        let _ = fs::remove_file("/tmp/system-updates.sqlite");
        package_promise_type.check_apply(
            PolicyMode::Enforce,
            &Parameters::new(
                "test".to_string(),
                serde_json::json!({
                    "campaign_type": "system",
                    "campaign_name": "My campaign",
                    "package_manager": "apt",
                    "event_id": "event_id",
                    "reboot_type": "as_needed",
                    "start": "2024-01-01T00:00:00Z",
                    "end": "2024-01-02T00:00:00Z",
                    "package_list": [],
                    "report_file": "/tmp/report.json",
                    "schedule_file": "/tmp/schedule.json",
                })
                .as_object()
                .unwrap()
                .clone(),
                PathBuf::from("/tmp"),
            ),
        )?;
        Ok(())
    }
}
