// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use crate::output::ResultOutput;
use crate::package_manager::apt::AptPackageManager;
use crate::package_manager::rpm::RpmPackageManager;
use crate::package_manager::{LinuxPackageManager, PackageDiff, PackageList, PackageSpec};
use anyhow::{bail, Result};
use std::io::BufRead;
use std::process::Command;

pub const NEED_RESTART_PATH: &str = "/usr/bin/needs-restarting";

/// Also supports dnf through yum wrapper, should only use compatible commands
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
            res.push_str("-");
            res.push_str(&v);
        }
        if let Some(a) = p.architecture {
            res.push_str(".");
            res.push_str(&a);
        }
        res
    }

    pub fn services_to_restart(&self) -> Result<Vec<String>> {
        let o = Command::new(NEED_RESTART_PATH).arg("--services").output()?;
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

    pub fn reboot_required(&self) -> Result<bool> {
        // only report whether a reboot is required (exit code 1) or not (exit code 0)
        Ok(!Command::new(NEED_RESTART_PATH)
            .arg("--reboothint")
            .status()?
            .success())
    }
}

impl LinuxPackageManager for YumPackageManager {
    fn list_installed(&self) -> Result<PackageList> {
        self.rpm.installed()
    }

    fn full_upgrade(&self) -> ResultOutput<()> {
        // https://serverfault.com/a/1075175
        let mut res = ResultOutput::new(Ok(()));
        let mut c = Command::new("yum");
        c.arg("--assumeyes").arg("update");
        let _ = res.command(c);
        res
    }

    fn security_upgrade(&self) -> ResultOutput<()> {
        // See https://access.redhat.com/solutions/10021
        let mut res = ResultOutput::new(Ok(()));
        let mut c = Command::new("yum");
        c.arg("--assumeyes").arg("--security").arg("update");
        let _ = res.command(c);
        res
    }

    fn upgrade(&self, packages: Vec<PackageSpec>) -> ResultOutput<()> {
        let mut res = ResultOutput::new(Ok(()));
        let mut c = Command::new("yum");
        c.arg("--assumeyes").arg("update").args(
            packages
                .into_iter()
                .map(|p| Self::package_spec_as_argument(p)),
        );
        let _ = res.command(c);
        res
    }
}
