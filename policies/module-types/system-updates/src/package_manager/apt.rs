// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use crate::package_manager::PackageSpec;
use anyhow::{bail, Result};
use std::env;
use std::io::BufRead;
use std::path::Path;
use std::process::Command;

pub const NEED_RESTART_PATH: &str = "/usr/bin/needs-restarting";

pub enum AptCompatibility {
    /// < 1.0
    Zero,
    /// >= 1.0
    OneZero,
}

pub struct Apt {
    compat: AptCompatibility,
}

impl Apt {
    pub fn new() -> Result<Self> {
        env::set_var("DEBIAN_FRONTEND", "noninteractive");
        env::set_var("APT_LISTCHANGES_FRONTEND", "none");

        let o = Command::new("apt-get").arg("--version").output()?;

        // FIXME
        let line = o.stdout.lines().next().unwrap()?;
        let v = line.split(' ').next().unwrap().next().unwrap();
        let compat = if v.starts_with('0') {
            AptCompatibility::Zero
        } else {
            AptCompatibility::OneZero
        };
        Ok(Self { compat })
    }

    pub fn system_update(&self) -> Result<()> {
        // https://superuser.com/questions/1412054/non-interactive-apt-upgrade
        Command::new("apt-get")
            .arg("--yes")
            .arg("--quiet")
            .args(["-o", "Dpkg::Options::=--force-confold"])
            .args(["-o", "Dpkg::Options::=--force-confdef"])
            .arg("dist-upgrade")
            .output()?;
        Ok(())
    }

    // apt install foo:i386=1.7.13+ds-2ubuntu1
    pub fn package_spec_as_argument(p: PackageSpec) -> String {
        let mut res = p.name;
        if let Some(a) = p.architecture {
            res.push_str(":");
            res.push_str(&a);
        }
        if let Some(v) = p.version {
            res.push_str("=");
            res.push_str(&v);
        }
        res
    }

    pub fn packages_update(&self) -> Result<()> {
        Ok(())
    }

    pub fn services_to_restart(&self) -> Result<Vec<String>> {
        // Handled by apt
        Ok(vec![])
    }

    pub fn reboot_required(&self) -> Result<bool> {
        Ok(Path::new("/var/run/reboot-required").exists())
    }
}
