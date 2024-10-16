// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

mod campaign;
mod cli;
mod db;
mod hooks;
mod output;
mod package_manager;
mod scheduler;
mod state;
mod system;

use crate::campaign::fail_campaign;
use crate::{
    campaign::{check_update, FullSchedule},
    cli::Cli,
    package_manager::PackageManager,
};
use anyhow::{anyhow, Context};
use chrono::{DateTime, Duration, Utc};
use package_manager::PackageSpec;
use rudder_module_type::{
    cfengine::called_from_agent, parameters::Parameters, run_module, CheckApplyResult, ModuleType0,
    ModuleTypeMetadata, PolicyMode, ValidateResult,
};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::{env, path::PathBuf};

const MODULE_NAME: &str = env!("CARGO_PKG_NAME");

// Same as the python implementation
pub const MODULE_DIR: &str = "/var/rudder/system-update";

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize, Copy)]
#[serde(rename_all = "kebab-case")]
pub enum RebootType {
    #[serde(alias = "enabled")]
    Always,
    AsNeeded,
    ServicesOnly,
    Disabled,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize, Copy, Default)]
#[serde(rename_all = "kebab-case")]
pub enum CampaignType {
    #[default]
    /// Install all available upgrades
    SystemUpdate,
    /// Install all available security upgrades
    SecurityUpdate,
    /// Install the updates from the provided package list
    SoftwareUpdate,
}

/// Parameters required to schedule an event
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub struct ScheduleParameters {
    start: DateTime<Utc>,
    end: DateTime<Utc>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum Schedule {
    Scheduled(ScheduleParameters),
    Immediate,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct PackageParameters {
    #[serde(default)]
    campaign_type: CampaignType,
    /// Rely on the agent to detect the OS and chose the right package manager.
    /// Avoid multiplying the number of environment detection sources.
    package_manager: PackageManager,
    event_id: String,
    campaign_name: String,
    schedule: Schedule,
    reboot_type: RebootType,
    #[serde(default)]
    #[serde(skip_serializing_if = "Vec::is_empty")]
    package_list: Vec<PackageSpec>,
    #[serde(default)]
    #[serde(skip_serializing_if = "Option::is_none")]
    report_file: Option<PathBuf>,
    #[serde(default)]
    #[serde(skip_serializing_if = "Option::is_none")]
    schedule_file: Option<PathBuf>,
}

/// We store no state in the module itself.
///
/// We could store package manager state, but it is unnecessary for now
/// as we have few calls to this module anyway.
struct SystemUpdateModule {}

impl SystemUpdateModule {
    fn new() -> Self {
        Self {}
    }
}

impl ModuleType0 for SystemUpdateModule {
    fn metadata(&self) -> ModuleTypeMetadata {
        let meta = include_str!("../rudder_module_type.yml");
        let docs = include_str!("../README.md");
        ModuleTypeMetadata::from_metadata(meta)
            .expect("invalid metadata")
            .documentation(docs)
    }

    fn validate(&self, parameters: &Parameters) -> ValidateResult {
        // Parse as parameter types
        let p_parameters: PackageParameters =
            serde_json::from_value(Value::Object(parameters.data.clone()))
                .context("Parsing module parameters")?;
        assert!(!p_parameters.event_id.is_empty());
        // Not doing more checks here as we want to send the errors as "system-update reports".
        Ok(())
    }

    fn check_apply(&mut self, mode: PolicyMode, parameters: &Parameters) -> CheckApplyResult {
        assert!(self.validate(parameters).is_ok());
        let package_parameters: PackageParameters =
            serde_json::from_value(Value::Object(parameters.data.clone()))
                .context("Parsing module parameters")?;
        let agent_freq = Duration::minutes(parameters.agent_frequency_minutes as i64);
        let full_schedule = match package_parameters.schedule {
            Schedule::Scheduled(ref s) => {
                FullSchedule::Scheduled(campaign::FullScheduleParameters {
                    start: s.start,
                    end: s.end,
                    node_id: parameters.node_id.clone(),
                    agent_frequency: agent_freq,
                })
            }
            Schedule::Immediate => FullSchedule::Immediate,
        };
        let report_file = package_parameters.report_file.clone();
        let r = {
            let i_am_root = parameters.node_id == "root";
            if mode == PolicyMode::Audit {
                Err(anyhow!("{} does not support audit mode", MODULE_NAME))
            } else if i_am_root && package_parameters.campaign_type != CampaignType::SoftwareUpdate
            {
                Err(anyhow!(
                    "System campaigns are not allowed on the Rudder root server, aborting."
                ))
            } else if package_parameters.campaign_type == CampaignType::SoftwareUpdate
                && package_parameters.package_list.is_empty()
            {
                Err(anyhow!(
                    "Software update without a package list. This is inconsistent, aborting."
                ))
            } else {
                check_update(
                    parameters.state_dir.as_path(),
                    full_schedule,
                    package_parameters,
                )
            }
        };

        if let Err(e) = r {
            // Send the report to server
            fail_campaign(&format!("{:?}", e), report_file)
        } else {
            r
        }
    }
}

/// Start runner
fn main() -> Result<(), anyhow::Error> {
    env::set_var("LC_ALL", "C");

    if called_from_agent() {
        run_module(SystemUpdateModule::new())
    } else {
        // The CLI does not use the module API
        Cli::run()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use pretty_assertions::assert_eq;
    use std::{fs, path::Path};

    #[test]
    fn test_module_type() {
        let module = SystemUpdateModule::new();

        let data1 = PackageParameters {
            campaign_type: CampaignType::SystemUpdate,
            package_manager: PackageManager::Yum,
            event_id: "30c87fee-f6b0-42b1-9adb-be067217f1a9".to_string(),
            campaign_name: "My campaign".to_string(),
            schedule: Schedule::Immediate,
            reboot_type: RebootType::AsNeeded,
            package_list: vec![],
            report_file: None,
            schedule_file: None,
        };
        let data1 = serde_json::from_str(&serde_json::to_string(&data1).unwrap()).unwrap();
        let mut reference1 = Parameters::new("node".to_string(), data1, PathBuf::from("/tmp"));
        reference1.temporary_dir = "/var/rudder/tmp/".into();
        reference1.backup_dir = "/var/rudder/modified-files/".into();

        let test1 = fs::read_to_string(Path::new("tests/parameters/immediate.json")).unwrap();
        let parameters1: Parameters = serde_json::from_str(&test1).unwrap();

        assert_eq!(parameters1, reference1);
        assert!(module.validate(&parameters1).is_ok());

        let data2 = PackageParameters {
            campaign_type: CampaignType::SoftwareUpdate,
            package_manager: PackageManager::Zypper,
            event_id: "30c87fee-f6b0-42b1-9adb-be067217f1a9".to_string(),
            campaign_name: "My campaign".to_string(),
            schedule: Schedule::Scheduled(ScheduleParameters {
                start: "2021-01-01T00:00:00Z".parse().unwrap(),
                end: "2023-01-01T00:00:00Z".parse().unwrap(),
            }),
            reboot_type: RebootType::AsNeeded,
            package_list: vec![
                PackageSpec::new(
                    "apache2".to_string(),
                    Some("2.4.29-1ubuntu4.14".to_string()),
                    None,
                ),
                PackageSpec::new(
                    "nginx".to_string(),
                    Some("1.14.0-0ubuntu1.7".to_string()),
                    None,
                ),
            ],
            report_file: Some(PathBuf::from("/tmp/report.json")),
            schedule_file: Some(PathBuf::from("/tmp/schedule.json")),
        };
        let data2 = serde_json::from_str(&serde_json::to_string(&data2).unwrap()).unwrap();
        let mut reference2 = Parameters::new("node".to_string(), data2, PathBuf::from("/tmp"));
        reference2.temporary_dir = "/var/rudder/tmp/".into();
        reference2.backup_dir = "/var/rudder/modified-files/".into();
        reference2.agent_frequency_minutes = 30;

        let test2 = fs::read_to_string(Path::new("tests/parameters/scheduled.json")).unwrap();
        let parameters2: Parameters = serde_json::from_str(&test2).unwrap();

        assert_eq!(parameters2, reference2);
        assert!(module.validate(&parameters2).is_ok());
    }
}
