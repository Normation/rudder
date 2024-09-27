// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use crate::{
    db::PackageDatabase,
    hooks::Hooks,
    output::{Report, ScheduleReport},
    package_manager::{LinuxPackageManager, PackageSpec},
    scheduler,
    system::System,
    CampaignType, PackageParameters, RebootType,
};
use anyhow::Result;
use chrono::{DateTime, Duration, Utc};
use rudder_module_type::{rudder_debug, Outcome};
use std::{fs, path::Path};

/// How long to keep events in the database
const RETENTION_DAYS: u32 = 60;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FullScheduleParameters {
    pub(crate) start: DateTime<Utc>,
    pub(crate) end: DateTime<Utc>,
    pub(crate) node_id: String,
    pub(crate) agent_frequency: Duration,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum FullSchedule {
    Scheduled(FullScheduleParameters),
    Immediate,
}

/// Called at each module run
///
/// The returned outcome is not linked to the success of the update, but to the success of the
/// process. The update itself can fail, but the process can be successful.
// FIXME: send all errors as reports
pub fn check_update(
    state_dir: &Path,
    schedule: FullSchedule,
    p: PackageParameters,
) -> Result<Outcome> {
    let mut db = PackageDatabase::new(Some(Path::new(state_dir)))?;
    rudder_debug!("Cleaning events older than {} days", RETENTION_DAYS);
    db.clean(Duration::days(RETENTION_DAYS as i64))?;
    let pm = p.package_manager.get()?;

    let schedule_datetime = match schedule {
        FullSchedule::Immediate => Utc::now(),
        FullSchedule::Scheduled(ref s) => {
            scheduler::splayed_start(s.start, s.end, s.agent_frequency, s.node_id.as_str())?
        }
    };
    let already_scheduled = db.schedule_event(&p.event_id, &p.campaign_name, schedule_datetime)?;

    // Update should have started already
    let now = Utc::now();
    if schedule == FullSchedule::Immediate || now >= schedule_datetime {
        let do_update = db.start_event(&p.event_id, now)?;
        if do_update {
            let report = update(pm, p.reboot_type, p.campaign_type, p.package_list)?;
            db.store_report(&p.event_id, &report)?;
        }

        // Update takes time
        let do_post_actions = db.post_event(&p.event_id)?;

        if do_post_actions {
            let init_report = db.get_report(&p.event_id)?;
            let report = post_update(init_report)?;
            db.store_report(&p.event_id, &report)?;

            if let Some(ref f) = p.report_file {
                // Write the report into the destination tmp file
                fs::write(f, serde_json::to_string(&report)?.as_bytes())?;
            }

            let now_finished = Utc::now();
            db.sent(&p.event_id, now_finished)?;

            // The repaired status is the trigger to read and send the report.
            Ok(Outcome::Repaired("Update has run".to_string()))
        } else {
            Ok(Outcome::Success(None))
        }
    } else {
        // Not the time yet, send the schedule if pending.
        if !already_scheduled {
            let report = ScheduleReport::new(schedule_datetime);
            if let Some(ref f) = p.schedule_file {
                // Write the report into the destination tmp file
                fs::write(f, serde_json::to_string(&report)?.as_bytes())?;
            }
            Ok(Outcome::Repaired("Send schedule".to_string()))
        } else {
            Ok(Outcome::Success(None))
        }
    }
}

/// Actually start the upgrade process immediately
fn update(
    mut pm: Box<dyn LinuxPackageManager>,
    reboot_type: RebootType,
    campaign_type: CampaignType,
    packages: Vec<PackageSpec>,
) -> Result<Report> {
    let mut report = Report::new();

    let pre_result = Hooks::PreUpgrade.run();
    report.step(pre_result);
    // Pre-run hooks are a blocker
    if report.is_err() {
        report.stderr("Pre-run hooks failed, aborting upgrade");
        return Ok(report);
    }

    // We consider failure to probe system state a blocking error
    let before = pm.list_installed();
    let before_list = match before.inner {
        Ok(ref l) => Some(l.clone()),
        _ => None,
    };
    report.step(before);
    if report.is_err() {
        report.stderr("Failed to list installed packages, aborting upgrade");
        return Ok(report);
    }
    let before_list = before_list.unwrap();

    // Update package cache
    //
    // Don't fail on cache update failure
    let cache_result = pm.update_cache();
    report.step(cache_result);

    let update_result = match campaign_type {
        CampaignType::SystemUpdate => pm.full_upgrade(),
        CampaignType::SoftwareUpdate => pm.upgrade(packages),
        CampaignType::SecurityUpdate => pm.security_upgrade(),
    };
    report.step(update_result);

    let after = pm.list_installed();
    let after_list = match after.inner {
        Ok(ref l) => Some(l.clone()),
        _ => None,
    };
    report.step(after);
    if report.is_err() {
        report.stderr("Failed to list installed packages, aborting upgrade");
        return Ok(report);
    }
    let after_list = after_list.unwrap();

    // Compute package list diff
    report.diff(before_list.diff(after_list));

    // Now take system actions
    let system = System::new();

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
        let reboot_result = system.reboot();
        report.step(reboot_result);
        // Stop there
        return Ok(report);
    }

    let services = pm.services_to_restart();
    let services_list = match services.inner {
        Ok(ref p) => p.clone(),
        Err(ref e) => {
            eprintln!("{}", e);
            vec![]
        }
    };
    report.step(services);

    if (reboot_type == RebootType::ServicesOnly || reboot_type == RebootType::AsNeeded)
        && !services_list.is_empty()
    {
        let restart_result = system.restart_services(&services_list);
        // Don't fail on service restart failure
        report.step(restart_result);
    }

    Ok(report)
}

/// Can run just after upgrade, or at next run in case of reboot.
fn post_update(mut report: Report) -> Result<Report> {
    let post_result = Hooks::PostUpgrade.run();
    report.step(post_result);
    Ok(report)
}
