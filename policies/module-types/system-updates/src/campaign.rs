// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use crate::hooks::Hooks;
use crate::output::ScheduleReport;
use crate::{
    db::PackageDatabase,
    output::Report,
    package_manager::{LinuxPackageManager, PackageSpec},
    scheduler,
    system::System,
    CampaignType, PackageParameters, RebootType,
};
use anyhow::Result;
use chrono::{Duration, Utc};
use rudder_module_type::Outcome;
use std::{fs, path::Path};

/// Called at each module run
pub fn check_update(
    node_id: &str,
    state_dir: &Path,
    agent_freq: Duration,
    p: PackageParameters,
) -> Result<Outcome> {
    let mut db = PackageDatabase::new(Some(Path::new(state_dir)))?;
    db.clean(Duration::days(60))?;
    let pm = p.package_manager.get()?;

    let schedule_datetime = scheduler::splayed_start(p.start, p.end, agent_freq, node_id)?;
    let already_scheduled = db.schedule_event(&p.event_id, &p.campaign_name, schedule_datetime)?;

    // Update should have start/have started already
    let now = Utc::now();
    if now >= schedule_datetime {
        let do_update = db.start_event(&p.event_id, now)?;
        if do_update {
            let report = update(pm, p.reboot_type, p.campaign_type, p.package_list)?;
            db.store_report(&p.event_id, &report)?;
        }

        // Update takes time
        let now_post = Utc::now();
        let do_post_actions = db.post_event(&p.event_id, now_post)?;
        if do_post_actions {
            let init_report = db.get_report(&p.event_id)?;
            let report = post_update(init_report)?;
            db.store_report(&p.event_id, &report)?;

            // Write the report into the destination tmp file
            fs::write(p.report_file, serde_json::to_string(&report)?.as_bytes())?;

            // Post-update actions may take time
            let now_finished = Utc::now();
            db.sent(&p.event_id, now_finished)?;

            // The repaired status is the trigger to read and send it.
            Ok(Outcome::Repaired("Update has run".to_string()))
        } else {
            Ok(Outcome::Success(None))
        }
    } else {
        // Not the time yet, send the schedule if pending.
        if !already_scheduled {
            let report = ScheduleReport::new(schedule_datetime);
            fs::write(p.schedule_file, serde_json::to_string(&report)?.as_bytes())?;
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

    Hooks::PreUpgrade.run();

    let before = pm.list_installed()?;

    match campaign_type {
        CampaignType::System => pm.full_upgrade(),
        CampaignType::Software => pm.upgrade(packages),
        CampaignType::Security => pm.security_upgrade(),
    };

    let after = pm.list_installed()?;
    report.software_updated = before.diff(after);

    // Now take system actions
    let system = System::new();

    // FIXME: pouvoir envoyer le report même en cas d'erreur de hook

    Hooks::PreReboot.run();

    if reboot_type == RebootType::Always
        || (reboot_type == RebootType::AsNeeded && pm.reboot_pending()?)
    {
        system.reboot();
        // Stop there
        return Ok(report);
    }

    if reboot_type == RebootType::ServicesOnly || reboot_type == RebootType::AsNeeded {
        let s = pm.services_to_restart()?;
        if !s.is_empty() {
            system.restart_services(&s);
        }
    }

    Hooks::PostUpgrade.run();
    Ok(report)
}

/// Can run just after upgrade, or at next run in case of reboot.
fn post_update(report: Report) -> Result<Report> {
    Hooks::PostUpgrade.run();
    Ok(report)
}
