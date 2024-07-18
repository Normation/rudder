// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use std::{io::BufRead, process::Command};

use crate::output::ResultOutput;
use crate::package_manager::rpm::RpmPackageManager;
use crate::package_manager::{LinuxPackageManager, PackageList, PackageSpec};
use anyhow::{bail, Result};

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

    fn refresh(&self) -> ResultOutput<()> {
        let mut res = ResultOutput::new(Ok(()));
        let mut c = Command::new("zypper");
        c.arg("-non-interactive").arg("refresh");
        let _ = res.command(c);
        res
    }
}

impl LinuxPackageManager for ZypperPackageManager {
    fn list_installed(&self) -> Result<PackageList> {
        self.rpm.installed()
    }

    fn full_upgrade(&self) -> ResultOutput<()> {
        let mut res = ResultOutput::new(Ok(()));
        let mut c = Command::new("zypper");

        c.arg("--non-interactive").arg("--name").arg("update");

        let _ = res.command(c);
        res
    }

    fn security_upgrade(&self) -> ResultOutput<()> {
        let mut res = ResultOutput::new(Ok(()));
        let mut c = Command::new("zypper");

        c.arg("--non-interactive").arg("--category").arg("security").arg("patch");

        let _ = res.command(c);
        res
    }

    fn upgrade(&self, packages: Vec<PackageSpec>) -> ResultOutput<()> {
        let mut res = ResultOutput::new(Ok(()));
        let mut c = Command::new("zypper");

        c.arg("--non-interactive").arg("--name").arg("update");

        c.args(packages.into_iter().map(Self::package_spec_as_argument));

        let _ = res.command(c);
        res
    }

    fn reboot_pending(&self) -> Result<bool> {
        let o = Command::new("zypper").arg("ps").arg("-s").output()?;
        Ok(String::from_utf8_lossy(&o.stdout).contains("Reboot is suggested"))
    }

    fn services_to_restart(&self) -> Result<Vec<String>> {
        let o = Command::new("zypper").arg("ps").arg("-sss").output()?;
        if !o.status.success() {
            bail!("TODO");
        }
        // One service name per line
        o.stdout
            .lines()
            .map(|s| {
                s.map(|service| service.trim().to_string())
                    .map_err(|e| e.into())
            })
            .collect()
    }
}
