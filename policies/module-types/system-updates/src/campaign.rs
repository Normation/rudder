// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use crate::hooks::Hooks;
use crate::output::ScheduleReport;
use crate::{
    db::PackageDatabase,
    output::{Report, Status},
    package_manager::{LinuxPackageManager, PackageSpec},
    scheduler,
    system::System,
    CampaignType, PackageParameters, RebootType, MODULE_DIR,
};
use anyhow::Result;
use chrono::{DateTime, Duration, Utc};
use rudder_module_type::Outcome;
use std::str::FromStr;
use std::{
    fmt::{Display, Formatter},
    fs,
    path::Path,
    process,
};
// FIXME: reprise en cas d'interruption à n'importe quel moment

// Stages:
//
// * `pre-upgrade` hooks
// * Before actual start. Send the schedule to the server.
// * Running upgrade (can take tens of minutes)
// * Upgrade is finished
// * [service restart] (if needed)
// * `pre-reboot` hooks
// * [reboot] (if needed)
//
// can be a different agent run

// * `post-upgrade` hooks

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum UpdateStatus {
    // Only starts when the update actually starts
    // Don't store anything about scheduled updates
    Running,
    PendingReport,
    Completed,
}

impl Display for UpdateStatus {
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Self::Running => "running",
            Self::Completed => "completed",
            Self::PendingReport => "pending-report",
        })
    }
}

impl FromStr for UpdateStatus {
    type Err = std::io::Error;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        match s {
            "started" => Ok(Self::Running),
            "completed" => Ok(Self::Completed),
            "pending-report" => Ok(Self::PendingReport),
            _ => Err(std::io::Error::new(
                std::io::ErrorKind::InvalidInput,
                "Invalid input",
            )),
        }
    }
}

/// Called at each module run
pub fn check_update(node_id: &str, state_dir: &Path, agent_freq: Duration, p: PackageParameters) -> Result<Outcome> {
    let mut db = PackageDatabase::new(Some(Path::new(state_dir)))?;
    db.clean(Duration::days(60))?;

    let pm = p.package_manager.get()?;

    let start_run = scheduler::splayed_start(p.start, p.end, agent_freq, node_id)?;
    let now: DateTime<Utc> = Utc::now();

    // Update should have start/have started already
    if now >= start_run {
        let r = update(
            pm,
            &p.event_id,
            &mut db,
            p.reboot_type,
            node_id,
            p.campaign_type,
            p.package_list,
        )?;
        let r = post_update(&p.event_id, &mut db)?;
        if let Some(report) = r {
            // Write the report into the destination tmp file
            fs::write(p.report_file, serde_json::to_string(&report)?.as_bytes())?;
            // The repaired status is the trigger to read and send it.
            Ok(Outcome::Repaired("TODO".to_string()))
        } else {
            Ok(Outcome::Success(None))
        }
    } else {
        // Not the time yet, send the schedule
        let report = ScheduleReport::new(start_run);
        if !p.schedule_file.exists() {
            fs::write(p.schedule_file, serde_json::to_string(&report)?.as_bytes())?;
        }
        Ok(Outcome::Success(None))
    }
}

/// Actually start the upgrade process immediately
pub fn update(
    pm: Box<dyn LinuxPackageManager>,
    event_id: &str,
    db: &mut PackageDatabase,
    reboot_type: RebootType,
    node_id: &str,
    campaign_type: CampaignType,
    packages: Vec<PackageSpec>,
) -> Result<Option<Report>> {
    let already_started = db.start_event(event_id)?;
    if already_started {
        return Ok(None);
    }

    let mut report = Report::new();

    if node_id == "root" {
        report.errors = Some(
            "# System campaign are not supported on the Rudder root server. Skipping.".to_string(),
        );
        report.status = Status::Error;
        return Ok(Some(report));
    }

    if campaign_type == CampaignType::Software && packages.is_empty() {
        report.errors = Some(
            "# Software update without a package list. This is inconsistent, aborting.".to_string(),
        );
        report.status = Status::Error;
        return Ok(Some(report));
    }

    // pre-upgrade hooks
    let hook_res = Hooks::PreUpgrade.run();

    let before = pm.list_installed()?;

    let r = match campaign_type {
        CampaignType::System => pm.full_upgrade(),
        CampaignType::Software => pm.upgrade(packages),
        CampaignType::Security => pm.security_upgrade(),
    };

    let after = pm.list_installed()?;
    report.software_updated = before.diff(after);

    // Now take system actions
    let system = System::new();

    // FIXME: pouvoir envoyer le report même en cas d'erreur de hook

    let hook_res = Hooks::PreReboot.run();

    if reboot_type == RebootType::Always
        || (reboot_type == RebootType::AsNeeded && pm.reboot_pending()?)
    {
        system.reboot();
        // Stop there
        return Ok(None);
    }

    if reboot_type == RebootType::ServicesOnly || reboot_type == RebootType::AsNeeded {
        let s = pm.services_to_restart()?;
        if !s.is_empty() {
            system.restart_services(&s);
        }
    }

    let hook_res = Hooks::PostUpgrade.run();

    db.store_report(event_id, &report)?;
    Ok(None)
}

/// Can run just after upgrade, or at next run in case of reboot
pub fn post_update(event_id: &str, db: &mut PackageDatabase) -> Result<Option<Report>> {
    // TODO read report and enrich
    // FIXME locking

    let report = db.get_report(event_id)?;

    let hook_res = Hooks::PostUpgrade.run();

    // Final version
    db.store_report(event_id, &report)?;

    Ok(Some(report))
}

/*

def run_action(package_manager, reboot, start, end, package_list, node_id, campaign_id):
    if not schedule_done:
        schedule = package_manager.send_schedule(start)

    # Common to update and report
    if finished:
        if not sent and os.path.exists(path):
            output = path
            # Post-hook
            # Only add log, don't change behavior or reporting
            report = package_manager.get_file('report')
            (result, o, e) = run_hooks(POST_HOOK_DIR)
            report['output'] += o
            report['errors'] += e
            package_manager.store_file('report', report)
            package_manager.set_sent()
            message = 'Sending update report for update started at ' + str(
                locked
            )
        else:
            message = 'Update report already sent at ' + str(sent)
        outcome = 'result_success'
    elif locked:
        message = 'Update is running since ' + str(locked)
    else:
        # not locked = not started
        if should_run(start, end):
            message = 'Running system update'
            # Pre-hooks
            report = {}
            (is_ok, report['output'], report['errors']) = run_hooks(
                PRE_HOOK_DIR
            )
            if not is_ok:
                report['status'] = 'error'
            package_manager.store_file('report', report)
            if is_ok:
                output = package_manager.run_update(reboot, package_list, node_id)
                if node_id == 'root':
                    outcome = 'result_error'
                    message = "System update campaign are not supported on the Rudder root server."
                else:
                    outcome = 'result_repaired'
            else:
                output = package_manager.get_file_path('report')
                message = 'Aborted system update due to failed pre-hooks'
                outcome = 'result_error'
        else:
            if is_past(start):
                message = 'Update should have run at ' + str(start)
            else:
                message = 'Update will run at ' + str(start)
 */
