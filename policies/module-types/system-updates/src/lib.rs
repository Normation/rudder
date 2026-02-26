// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

pub mod campaign;
mod cli;
pub mod db;
mod hooks;
pub mod output;
pub mod package_manager;
mod runner;
mod scheduler;
pub mod state;
pub mod systemd;

use crate::{
    campaign::{RETENTION_DAYS, RunnerParameters},
    cli::Cli,
    db::PackageDatabase,
    package_manager::PackageManager,
    runner::Runner,
};
use anyhow::{Context, Result, anyhow};
use chrono::{DateTime, Duration, Utc};
use package_manager::PackageSpec;
use rudder_module_type::{
    CheckApplyResult, ModuleType0, ModuleTypeMetadata, PolicyMode, ValidateResult,
    cfengine::called_from_agent, parameters::Parameters, rudder_debug, run_module,
};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::{env, path::PathBuf, str::FromStr};

const MODULE_NAME: &str = env!("CARGO_PKG_NAME");

// Same as the python implementation
pub const MODULE_DIR: &str = "/var/rudder/system-update";

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize, Copy, Default)]
#[serde(rename_all = "kebab-case")]
pub enum RebootType {
    #[serde(alias = "enabled")]
    Always,
    AsNeeded,
    ServicesOnly,
    #[default]
    Disabled,
}

impl FromStr for RebootType {
    type Err = std::io::Error;

    fn from_str(s: &str) -> anyhow::Result<Self, Self::Err> {
        match s {
            "always" => Ok(Self::Always),
            "as-needed" => Ok(Self::AsNeeded),
            "services-only" => Ok(Self::ServicesOnly),
            "disabled" => Ok(Self::Disabled),
            _ => Err(std::io::Error::new(
                std::io::ErrorKind::InvalidInput,
                "Invalid input",
            )),
        }
    }
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
    pub start: DateTime<Utc>,
    pub end: DateTime<Utc>,
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
    pub campaign_type: CampaignType,
    /// Rely on the agent to detect the OS and chose the right package manager.
    /// Avoid multiplying the number of environment detection sources.
    pub package_manager: PackageManager,
    pub event_id: String,
    pub campaign_name: String,
    pub schedule: Schedule,
    pub reboot_type: RebootType,
    #[serde(default)]
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub package_list: Vec<PackageSpec>,
    #[serde(default)]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub report_file: Option<PathBuf>,
    #[serde(default)]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub schedule_file: Option<PathBuf>,
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
        let pm = package_parameters.package_manager.get()?;

        let i_am_root = parameters.node_id == "root";
        if mode == PolicyMode::Audit {
            Err(anyhow!("{} does not support audit mode", MODULE_NAME))
        } else if i_am_root && package_parameters.campaign_type != CampaignType::SoftwareUpdate {
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
            let db = PackageDatabase::new(Some(parameters.state_dir.as_path()))?;
            rudder_debug!("Cleaning events older than {} days", RETENTION_DAYS);
            db.clean(Duration::days(RETENTION_DAYS as i64))?;
            let agent_freq = Duration::minutes(parameters.agent_frequency_minutes as i64);
            let rp =
                RunnerParameters::new(package_parameters, parameters.node_id.clone(), agent_freq);
            let pid = std::process::id();
            let mut runner = Runner::new(db, pm, rp, pid);
            runner.run()
        }
    }
}

/// Start runner
pub fn entry() -> Result<(), anyhow::Error> {
    // SAFETY: The module is single-threaded.
    unsafe {
        env::set_var("LC_ALL", "C");
    }

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
