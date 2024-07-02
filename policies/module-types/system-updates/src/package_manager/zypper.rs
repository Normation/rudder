// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use crate::package_manager::PackageSpec;
use anyhow::{bail, Result};
use std::io::BufRead;
use std::process::Command;

pub struct Zypper {}

impl Zypper {
    pub fn system_update(&self) -> Result<()> {
        Command::new("zypper").arg("refresh").output()?;
        Command::new("zypper")
            .arg("--non-interactive")
            .arg("update")
            .output()?;
        Ok(())
    }

    pub fn packages_update(&self) -> Result<()> {
        Command::new("zypper").arg("refresh").output()?;
        Command::new("zypper")
            .arg("--non-interactive")
            .arg("--name")
            .arg("update")
            // FIXME
            .args(vec![])
            .output()?;
        Ok(())
    }

    pub fn package_spec_as_argument(p: PackageSpec) -> String {
        let mut res = p.name;

        if let Some(a) = p.architecture {
            res.push_str(".");
            res.push_str(&a);
        }
        if let Some(v) = p.version {
            res.push_str("=");
            res.push_str(&v);
        }
        res
    }

    pub fn services_to_restart(&self) -> Result<Vec<String>> {
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

    /*
     def is_reboot_needed(self):
       (code, out, err) = run([self.ZYPPER_PATH, 'ps', '-s'])
       if code != 0:
           return True
       if out.find('Reboot is suggested') != -1:
           return True
       return False
    */

    pub fn reboot_required(&self) -> Result<bool> {
        let o = Command::new("zypper").arg("ps").arg("-s").output()?;
        Ok(String::from_utf8_lossy(&o.stdout).contains("Reboot is suggested"))
    }
}
