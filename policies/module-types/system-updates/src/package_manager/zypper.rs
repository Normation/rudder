// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use std::process::{Command, Output};

use crate::{
    output::ResultOutput,
    package_manager::{
        rpm::RpmPackageManager, LinuxPackageManager, PackageList, PackageManager, PackageSpec,
    },
};
use anyhow::Result;

/// We need to be compatible with:
///
/// * SLES 12 SP5+
/// * SLES 15 SP2+

pub struct ZypperPackageManager {
    rpm: RpmPackageManager,
}

impl ZypperPackageManager {
    pub fn new() -> Self {
        let rpm = RpmPackageManager::new();
        Self { rpm }
    }

    pub fn package_spec_as_argument(p: PackageSpec) -> String {
        let mut res = p.name;

        if let Some(a) = p.architecture {
            res.push('.');
            res.push_str(&a);
        }
        if let Some(v) = p.version {
            res.push('=');
            res.push_str(&v);
        }
        res
    }

    fn refresh(&self) -> ResultOutput<Output> {
        let mut c = Command::new("zypper");
        c.arg("-non-interactive").arg("refresh");
        ResultOutput::command(c)
    }
}

impl LinuxPackageManager for ZypperPackageManager {
    fn list_installed(&mut self) -> Result<PackageList> {
        self.rpm.installed()
    }

    fn full_upgrade(&mut self) -> ResultOutput<()> {
        let mut res = self.refresh();
        // FIXME fail in case of refresh error?
        let mut c = Command::new("zypper");
        c.arg("--non-interactive").arg("--name").arg("update");
        res.step(ResultOutput::command(c));
        res.clear_ok()
    }

    fn security_upgrade(&mut self) -> ResultOutput<()> {
        let mut res = self.refresh();
        let mut c = Command::new("zypper");
        c.arg("--non-interactive")
            .arg("--category")
            .arg("security")
            .arg("patch");
        res.step(ResultOutput::command(c));
        res.clear_ok()
    }

    fn upgrade(&mut self, packages: Vec<PackageSpec>) -> ResultOutput<()> {
        let mut res = self.refresh();
        let mut c = Command::new("zypper");
        c.arg("--non-interactive").arg("--name").arg("update");
        c.args(packages.into_iter().map(Self::package_spec_as_argument));
        res.step(ResultOutput::command(c));
        res.clear_ok()
    }

    fn reboot_pending(&self) -> Result<bool> {
        let o = Command::new("zypper").arg("ps").arg("-s").output()?;
        Ok(String::from_utf8_lossy(&o.stdout).contains("Reboot is suggested"))
    }

    fn services_to_restart(&self) -> Result<Vec<String>> {
        let mut c = Command::new("zypper");
        c.arg("ps").arg("-sss");
        PackageManager::parse_one_by_line(c)
    }
}
