// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use std::process::{Command, Output};

use crate::{
    output::ResultOutput,
    package_manager::{rpm::RpmPackageManager, LinuxPackageManager, PackageList, PackageSpec},
};

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
        c.arg("--non-interactive").arg("refresh");
        ResultOutput::command(c)
    }
}

impl LinuxPackageManager for ZypperPackageManager {
    fn list_installed(&mut self) -> ResultOutput<PackageList> {
        self.rpm.installed()
    }

    fn full_upgrade(&mut self) -> ResultOutput<()> {
        let res = self.refresh();

        // FIXME fail in case of refresh error?
        let mut c = Command::new("zypper");
        c.arg("--non-interactive").arg("--name").arg("update");
        let res_update = ResultOutput::command(c);
        let final_res = res.step(res_update);
        final_res.clear_ok()
    }

    fn security_upgrade(&mut self) -> ResultOutput<()> {
        let res = self.refresh();
        let mut c = Command::new("zypper");
        c.arg("--non-interactive")
            .arg("--category")
            .arg("security")
            .arg("patch");
        let res_update = ResultOutput::command(c);
        let final_res = res.step(res_update);
        final_res.clear_ok()
    }

    fn upgrade(&mut self, packages: Vec<PackageSpec>) -> ResultOutput<()> {
        let res = self.refresh();
        let mut c = Command::new("zypper");
        c.arg("--non-interactive").arg("--name").arg("update");
        c.args(packages.into_iter().map(Self::package_spec_as_argument));
        let res_update = ResultOutput::command(c);
        let final_res = res.step(res_update);
        final_res.clear_ok()
    }

    fn reboot_pending(&self) -> ResultOutput<bool> {
        let mut c = Command::new("zypper");
        c.arg("ps").arg("-s");
        let res = ResultOutput::command(c);

        let (r, o, e) = (res.inner, res.stdout, res.stderr);
        let res = match r {
            Ok(_) => Ok(o.iter().any(|l| l.contains("Reboot is suggested"))),
            Err(e) => Err(e),
        };
        ResultOutput::new_output(res, o, e)
    }

    fn services_to_restart(&self) -> ResultOutput<Vec<String>> {
        let mut c = Command::new("zypper");
        c.arg("ps").arg("-sss");
        let res = ResultOutput::command(c);
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
