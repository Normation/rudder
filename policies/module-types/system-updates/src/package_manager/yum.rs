// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use std::process::Command;

use anyhow::Result;

use crate::{
    output::ResultOutput,
    package_manager::{
        rpm::RpmPackageManager, LinuxPackageManager, PackageList, PackageManager, PackageSpec,
    },
};

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
    pub fn new() -> Self {
        let rpm = RpmPackageManager::new();
        Self { rpm }
    }

    fn package_spec_as_argument(p: PackageSpec) -> String {
        let mut res = p.name;
        if let Some(v) = p.version {
            res.push('-');
            res.push_str(&v);
        }
        if let Some(a) = p.architecture {
            res.push('.');
            res.push_str(&a);
        }
        res
    }
}

impl LinuxPackageManager for YumPackageManager {
    fn list_installed(&mut self) -> Result<PackageList> {
        self.rpm.installed()
    }

    fn full_upgrade(&mut self) -> ResultOutput<()> {
        // https://serverfault.com/a/1075175
        let mut c = Command::new("yum");
        c.arg("--assumeyes").arg("update");
        ResultOutput::command(c).clear_ok()
    }

    /// `yum install yum-plugin-security` is only necessary on RHEL < 7, which are not supported.
    fn security_upgrade(&mut self) -> ResultOutput<()> {
        // See https://access.redhat.com/solutions/10021
        let mut c = Command::new("yum");
        c.arg("--assumeyes").arg("--security").arg("update");
        ResultOutput::command(c).clear_ok()
    }

    fn upgrade(&mut self, packages: Vec<PackageSpec>) -> ResultOutput<()> {
        let mut c = Command::new("yum");
        c.arg("--assumeyes")
            .arg("update")
            .args(packages.into_iter().map(Self::package_spec_as_argument));
        ResultOutput::command(c).clear_ok()
    }

    fn reboot_pending(&self) -> ResultOutput<bool> {
        // only report whether a reboot is required (exit code 1) or not (exit code 0)
        let mut c = Command::new("needs-restarting");
        c.arg("--reboothint");
        let res = ResultOutput::command(c);

        match res.inner {
            Ok(ref o) => res.result(Ok(!o.status.success())),
            Err(ref e) => res.result(Err(e)),
        }
    }

    fn services_to_restart(&self) -> Result<Vec<String>> {
        let mut c = Command::new("needs-restarting");
        c.arg("--services");
        PackageManager::parse_one_by_line(c)
    }
}
