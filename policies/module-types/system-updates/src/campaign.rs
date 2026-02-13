// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

#![allow(clippy::borrowed_box)]

use crate::package_manager::{PackageId, PackageList};
use crate::{
    CampaignType, PackageParameters, RebootType, Schedule,
    db::PackageDatabase,
    hooks::{Hooks, RunHooks},
    output::{Report, ScheduleReport, Status},
    package_manager::{PackageSpec, UpdateManager},
    system::System,
};
use anyhow::Result;
use chrono::{DateTime, Duration, Utc};
use rudder_module_type::Outcome;
use std::collections::HashMap;
use std::{fs, path::PathBuf};

/// How long to keep events in the database
pub(crate) const RETENTION_DAYS: u32 = 60;

#[derive(Clone, Debug)]
pub struct RunnerParameters {
    pub campaign_type: FullCampaignType,
    pub event_id: String,
    pub campaign_name: String,
    pub schedule: FullSchedule,
    pub reboot_type: RebootType,
    pub report_file: Option<PathBuf>,
    pub schedule_file: Option<PathBuf>,
}

impl RunnerParameters {
    pub fn new(
        package_parameters: PackageParameters,
        node_id: String,
        agent_frequency: Duration,
    ) -> Self {
        Self {
            campaign_type: FullCampaignType::new(
                package_parameters.campaign_type,
                package_parameters.package_list,
                package_parameters.exclude_list,
            ),
            event_id: package_parameters.event_id,
            campaign_name: package_parameters.campaign_name,
            schedule: FullSchedule::new(&package_parameters.schedule, node_id, agent_frequency),
            reboot_type: package_parameters.reboot_type,
            report_file: package_parameters.report_file,
            schedule_file: package_parameters.schedule_file,
        }
    }

    pub fn new_immediate(package_parameters: PackageParameters) -> Self {
        Self {
            campaign_type: FullCampaignType::new(
                package_parameters.campaign_type,
                package_parameters.package_list,
                package_parameters.exclude_list,
            ),
            event_id: package_parameters.event_id,
            campaign_name: package_parameters.campaign_name,
            schedule: FullSchedule::Immediate,
            reboot_type: package_parameters.reboot_type,
            report_file: package_parameters.report_file,
            schedule_file: package_parameters.schedule_file,
        }
    }
}

#[derive(Clone, Debug)]
pub struct FullCampaignType {
    pub include: CampaignTarget,
    pub exclude: Vec<PackageSpec>,
}

impl FullCampaignType {
    pub fn new_security() -> Self {
        Self {
            include: CampaignTarget::SecurityUpdate,
            exclude: Vec::new(),
        }
    }

    pub fn new_system() -> Self {
        Self {
            include: CampaignTarget::SystemUpdate,
            exclude: Vec::new(),
        }
    }
}

#[derive(Clone, Debug)]
pub enum CampaignTarget {
    /// Install all available upgrades
    SystemUpdate,
    /// Install all available security upgrades
    SecurityUpdate,
    /// Install the updates from the provided package list
    List(Vec<PackageSpec>),
}

impl FullCampaignType {
    pub fn new(c: CampaignType, include: Vec<PackageSpec>, exclude: Vec<PackageSpec>) -> Self {
        FullCampaignType {
            include: match c {
                CampaignType::SystemUpdate => CampaignTarget::SystemUpdate,
                CampaignType::SecurityUpdate => CampaignTarget::SecurityUpdate,
                CampaignType::SoftwareUpdate => CampaignTarget::List(include),
            },
            exclude,
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FullScheduleParameters {
    pub start: DateTime<Utc>,
    pub end: DateTime<Utc>,
    pub node_id: String,
    pub agent_frequency: Duration,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum FullSchedule {
    Scheduled(FullScheduleParameters),
    Immediate,
}

impl FullSchedule {
    pub fn new(schedule: &Schedule, node_id: String, agent_frequency: Duration) -> Self {
        match schedule {
            Schedule::Scheduled(s) => FullSchedule::Scheduled(FullScheduleParameters {
                start: s.start,
                end: s.end,
                node_id,
                agent_frequency,
            }),
            Schedule::Immediate => FullSchedule::Immediate,
        }
    }
}

/// Called at each module run
///
/// The returned outcome is not linked to the success of the update, but to the success of the
/// process. The update itself can fail, but the process can be successful.
pub fn do_schedule(
    p: &RunnerParameters,
    db: &mut PackageDatabase,
    schedule_datetime: DateTime<Utc>,
) -> Result<Outcome> {
    db.schedule_event(&p.event_id, &p.campaign_name, schedule_datetime)?;
    let report = ScheduleReport::new(schedule_datetime);
    if let Some(ref f) = p.schedule_file {
        // Write the report into the destination tmp file
        fs::write(f, serde_json::to_string(&report)?.as_bytes())?;
    }
    Ok(Outcome::Repaired("Schedule has been sent".to_string()))
}

pub fn do_update(
    p: &RunnerParameters,
    db: &mut PackageDatabase,
    package_manager: &mut Box<dyn UpdateManager>,
    system: &Box<dyn System>,
) -> Result<bool> {
    db.start_event(&p.event_id, Utc::now())?;
    let (report, reboot) = update(package_manager, p.reboot_type, &p.campaign_type, system)?;
    db.schedule_post_event(&p.event_id, &report)?;
    Ok(reboot)
}

pub fn do_post_update(p: &RunnerParameters, db: &mut PackageDatabase) -> Result<Outcome> {
    db.post_event(&p.event_id)?;
    let init_report = db.get_report(&p.event_id)?;
    let report = post_update(init_report)?;

    if let Some(ref f) = p.report_file {
        // Write the report into the destination tmp file
        fs::write(f, serde_json::to_string(&report)?.as_bytes())?;
    }

    let now_finished = Utc::now();
    db.completed(&p.event_id, now_finished, &report)?;

    // The repaired status is the trigger to read and send the report.
    Ok(Outcome::Repaired("Update has run".to_string()))
}

/// Shortcut method to send an error report directly
pub fn fail_campaign(reason: &str, report_file: Option<&PathBuf>) -> Result<Outcome> {
    let mut report = Report::new();
    report.stderr(reason);
    report.status = Status::Error;
    if let Some(ref f) = report_file {
        // Write the report into the destination tmp file
        fs::write(f, serde_json::to_string(&report)?.as_bytes())?;
    }
    Ok(Outcome::Repaired("Send error".to_string()))
}

/// Actually start the upgrade process immediately
fn update(
    pm: &mut Box<dyn UpdateManager>,
    reboot_type: RebootType,
    campaign_type: &FullCampaignType,
    system: &Box<dyn System>,
) -> Result<(Report, bool)> {
    let mut report = Report::new();

    let pre_result = Hooks::PreUpgrade.run();
    report.step(pre_result);
    // Pre-run hooks are a blocker
    if report.is_err() {
        report.stderr("Pre-run hooks failed, aborting upgrade");
        return Ok((report, false));
    }

    // We consider failure to probe system state a blocking error
    let before = pm.list_installed();
    let before_list = match before.inner {
        Ok(ref l) => Some(l.clone()),
        _ => None,
    };
    if report.is_err() {
        report.step(before);
        report.stderr("Failed to list installed packages, aborting upgrade");
        return Ok((report, false));
    }
    let before_list = before_list.unwrap();

    // Update package cache
    //
    // Don't fail on cache update failure
    let cache_result = pm.update_cache();
    report.step(cache_result);

    let update_result = pm.upgrade(campaign_type);
    let update_details: Option<HashMap<PackageId, String>> = match update_result.inner {
        Err(_) => None,
        Ok(ref h) => h.clone(),
    };
    report.step(update_result);

    let after = pm.list_installed();
    let after_list = match after.inner {
        Ok(ref l) => Some(l.clone()),
        _ => None,
    };
    let after_failed = after.inner.is_err();
    // The report can be in error state, we only want to fail
    // if the package listing failed.
    if after_failed {
        report.step(after);
        report.stderr("Failed to list installed packages, aborting upgrade");
        return Ok((report, false));
    }
    let after_list: PackageList = after_list.unwrap();

    // Compute package list diff
    report.diff(before_list.diff(after_list), update_details);

    // Now take system actions
    let pre_reboot_result = Hooks::PreReboot.run();
    report.step(pre_reboot_result);

    let pending = pm.reboot_pending();
    let is_pending = match pending.inner {
        Ok(p) => p,
        Err(_) => {
            report.stderr("Failed to check if a reboot is pending");
            true
        }
    };
    report.step(pending);

    if reboot_type == RebootType::Always || (reboot_type == RebootType::AsNeeded && is_pending) {
        // Stop there
        return Ok((report, true));
    }

    let services = pm.services_to_restart();
    let services_list = match services.inner {
        Ok(ref p) => p.clone(),
        Err(ref e) => {
            eprintln!("{e}");
            vec![]
        }
    };
    report.step(services);

    if (reboot_type == RebootType::ServicesOnly || reboot_type == RebootType::AsNeeded)
        && !services_list.is_empty()
    {
        report.stdout(format!(
            "Restarting services: {}",
            &services_list.join(", ")
        ));
        let restart_result = system.restart_services(&services_list);
        // Don't fail on service restart failure
        report.step(restart_result);
    }

    Ok((report, false))
}

/// Can run just after upgrade, or at next run in case of reboot.
fn post_update(mut report: Report) -> Result<Report> {
    let post_result = Hooks::PostUpgrade.run();
    report.step(post_result);
    Ok(report)
}

#[cfg(test)]
mod tests {
    // FIXME tests for do_* with mock pm
}
