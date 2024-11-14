// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use std::process::Command;

use anyhow::Result;

use crate::output::CommandBehavior;
use crate::{
    campaign::FullCampaignType,
    output::ResultOutput,
    package_manager::{rpm::RpmPackageManager, LinuxPackageManager, PackageList, PackageSpec},
};
#[cfg(not(debug_assertions))]
use rudder_module_type::ensure_root_user;

/// We need to be compatible with:
///
/// * RHEL 7+
/// * Amazon Linux 2+
///
/// Also supports DNF through YUM wrapper, we should only use compatible commands.
pub struct YumPackageManager {
    rpm: RpmPackageManager,
}

impl YumPackageManager {
    pub fn new() -> Result<Self> {
        #[cfg(not(debug_assertions))]
        ensure_root_user()?;
        let rpm = RpmPackageManager::new();
        Ok(Self { rpm })
    }

    fn package_spec_as_argument(p: &PackageSpec) -> String {
        let mut res = p.name.clone();
        if let Some(ref v) = p.version {
            res.push('-');
            res.push_str(v);
        }
        if let Some(ref a) = p.architecture {
            res.push('.');
            res.push_str(a);
        }
        res
    }

    fn full_upgrade(&mut self) -> ResultOutput<()> {
        // https://serverfault.com/a/1075175
        let mut c = Command::new("yum");
        c.arg("--assumeyes").arg("update");
        ResultOutput::command(c, CommandBehavior::FailOnErrorCode).clear_ok()
    }

    /// `yum install yum-plugin-security` is only necessary on RHEL < 7, which are not supported.
    fn security_upgrade(&mut self) -> ResultOutput<()> {
        // See https://access.redhat.com/solutions/10021
        let mut c = Command::new("yum");
        c.arg("--assumeyes").arg("--security").arg("update");
        ResultOutput::command(c, CommandBehavior::FailOnErrorCode).clear_ok()
    }

    fn software_upgrade(&mut self, packages: &[PackageSpec]) -> ResultOutput<()> {
        let mut c = Command::new("yum");
        c.arg("--assumeyes")
            .arg("update")
            .args(packages.iter().map(Self::package_spec_as_argument));
        ResultOutput::command(c, CommandBehavior::FailOnErrorCode).clear_ok()
    }
}

impl LinuxPackageManager for YumPackageManager {
    fn list_installed(&mut self) -> ResultOutput<PackageList> {
        self.rpm.installed()
    }

    fn upgrade(&mut self, update_type: &FullCampaignType) -> ResultOutput<()> {
        match update_type {
            FullCampaignType::SystemUpdate => self.full_upgrade(),
            FullCampaignType::SecurityUpdate => self.security_upgrade(),
            FullCampaignType::SoftwareUpdate(p) => self.software_upgrade(p),
        }
    }

    fn reboot_pending(&self) -> ResultOutput<bool> {
        // only report whether a reboot is required (exit code 1) or not (exit code 0)
        let mut c = Command::new("needs-restarting");
        c.arg("--reboothint");
        let res = ResultOutput::command(c, CommandBehavior::OkOnErrorCode);

        let (r, o, e) = (res.inner, res.stdout, res.stderr);
        let res = match r {
            Ok(o) => Ok(o.status.success()),
            Err(e) => Err(e),
        };
        ResultOutput::new_output(res, o, e)
    }

    fn services_to_restart(&self) -> ResultOutput<Vec<String>> {
        let mut c = Command::new("needs-restarting");
        c.arg("--services");
        let res = ResultOutput::command(c, CommandBehavior::FailOnErrorCode);
        let (r, o, e) = (res.inner, res.stdout, res.stderr);
        let res = match r {
            Ok(_) => {
                let services = o.iter().map(|s| s.trim().to_string()).collect();
                Ok(services)
            }
            Err(e) => Err(e),
        };
        ResultOutput::new_output(res, o, e)
    }
}
