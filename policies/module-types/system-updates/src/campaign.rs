// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use crate::db::{PackageDatabase, DB_DIR};
use crate::output::{Report, Status};
use crate::package_manager::{LinuxPackageManager, PackageSpec};
use crate::{scheduler, CampaignType, PackageParameters, RebootType};
use anyhow::Result;
use chrono::{DateTime, Duration, Utc};
use std::fmt::{Display, Formatter};
use std::fs;
use std::path::Path;

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum UpdateStatus {
    Started,
    PendingReport,
    Completed,
}

impl Display for UpdateStatus {
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Self::Started => "started",
            Self::Completed => "completed",
            Self::PendingReport => "pending-report",
        })
    }
}

/// Called at each module run
pub fn check_update(node_id: &str, agent_freq: Duration, p: PackageParameters) -> Result<()> {
    let db = PackageDatabase::new(Some(Path::new(DB_DIR)))?;
    let pm = p.provider.get()?;

    let start_run = scheduler::splayed_start(p.start, p.end, agent_freq, node_id)?;
    let now: DateTime<Utc> = Utc::now();

    if now >= start_run {
        let r = run_update(
            pm,
            &p.event_id,
            db,
            p.reboot_type,
            node_id,
            p.campaign_type,
            p.package_list,
        )?;
        // Write the report into the destination tmp file
        fs::write(p.report_file, serde_json::to_string(&r)?.as_bytes())?;
    } else {
        // FIXME: send schedule
    }
    Ok(())
}

/// Actually start the upgrade process immediately
pub fn run_update(
    pm: Box<dyn LinuxPackageManager>,
    event_id: &str,
    mut db: PackageDatabase,
    reboot_type: RebootType,
    node_id: &str,
    campaign_type: CampaignType,
    packages: Vec<PackageSpec>,
) -> Result<Report> {
    let already_started = db.start_event(event_id)?;

    let mut report = Report::new();

    if already_started {
        report.status = Status::Success;
        report.output = "# Upgrade already running, skipping".to_string();
        return Ok(report);
    }

    if node_id == "root" {
        report.output =
            "# System campaign are not supported on the Rudder root server. Skipping.".to_string();
        report.status = Status::Error;
        return Ok(report);
    }

    if campaign_type == CampaignType::Software && packages.is_empty() {
        report.output =
            "# Software update without a package list. This is inconsistent, aborting.".to_string();
        report.status = Status::Error;
        return Ok(report);
    }

    let before = pm.list_installed()?;
    db.store_packages(&event_id, &before)?;

    db.clean(Duration::days(60))?;
    Ok(report)
}

/*
   def run_update(self, reboot, package_list, node_id):

       # race condition here but we can live with that
       self.set_lock()

       before = self.installed()
       self.store_file('before', before)

       restart_services = (reboot == 'as_needed') or (
           reboot == 'services_only'
       )
       (code, output, errors) = self.update(packages, restart_services)

       after = self.installed()
       self.store_file('after', after)

       updates = self.diff(before, after)

       report['software-updated'] = updates

       if output:
           report['output'] += output
       if errors:
           report['errors'] += errors

       if code != 0:
           report['status'] = 'error'
           report[
               'errors'
           ] += '\n# Upgrade interrupted due to package manager failure\n'
           self.store_file('report', report)
           # Early return in case of failure
           # Don't try to restart or reboot
           return self.get_file_path('report')
       elif updates:
           report['status'] = 'repaired'
       else:
           report['status'] = 'success'

       is_reboot_needed = self.is_reboot_needed()

       if (
           reboot == 'as_needed' and not is_reboot_needed
       ) or reboot == 'services-only':
           services = self.services_to_restart()
           if services:
               (code, output, errors) = self.restart_services(services)
               if code != 0:
                   report['status'] = 'error'
               report['output'] += (
                   '\n' + 'Restarting services: ' + str(services)
               )
               if output:
                   report['output'] += '\n' + output
               if errors:
                   report['errors'] += '\n' + errors

       self.store_file('report', report)

       if reboot == 'always' or (reboot == 'as-needed' and is_reboot_needed):
           (is_ok, output, errors) = run_hooks(PRE_REBOOT_HOOK_DIR)
           report['output'] += output
           report['errors'] += errors
           if not is_ok:
               report['status'] = 'error'
               report[
                   'errors'
               ] += '\n# Reboot skipped due to pre-reboot hook failure\n'
               self.store_file('report', report)
               # Early return in case of failure
               # Don't try to restart or reboot
               return self.get_file_path('report')
           report['output'] += "\n# Rebooting the system now"
           report['errors'] += "\n# Rebooting the system now"
           # Store the report and reboot
           self.store_file('report', report)
           self.do_reboot()
       else:
           # consider report sent
           self.set_sent()

       return self.get_file_path('report')


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
