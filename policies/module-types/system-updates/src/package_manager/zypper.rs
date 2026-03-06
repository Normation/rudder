// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use std::process::Command;

use anyhow::Result;

use crate::output::{CommandBehavior, CommandCapture};
use crate::package_manager::PackageManager;
use crate::{
    campaign::FullCampaignType,
    output::ResultOutput,
    package_manager::{LinuxPackageManager, PackageList, PackageSpec, rpm::RpmPackageManager},
};
#[cfg(not(debug_assertions))]
use rudder_module_type::ensure_root_user;

/// We need to be compatible with:
///
/// * SLES 12 SP5+
/// * SLES 15 SP2+
pub struct ZypperPackageManager {
    rpm: RpmPackageManager,
}

impl ZypperPackageManager {
    pub fn new() -> Result<Self> {
        #[cfg(not(debug_assertions))]
        ensure_root_user()?;
        let rpm = RpmPackageManager::new();
        Ok(Self { rpm })
    }

    pub fn package_spec_as_argument(p: &PackageSpec) -> String {
        let mut res = p.name.clone();

        if let Some(ref a) = p.architecture {
            res.push('.');
            res.push_str(a);
        }
        if let Some(ref v) = p.version {
            res.push('=');
            res.push_str(v);
        }
        res
    }

    fn full_upgrade(&mut self) -> ResultOutput<()> {
        let mut c = Command::new("zypper");
        c.arg("--non-interactive").arg("update");
        let res_update = ResultOutput::command(
            c,
            CommandBehavior::FailOnErrorCode,
            CommandCapture::StdoutStderr,
        );
        res_update.clear_ok()
    }

    fn security_upgrade(&mut self) -> ResultOutput<()> {
        let mut c = Command::new("zypper");
        c.arg("--non-interactive")
            .arg("--category")
            .arg("security")
            .arg("patch");
        let res_update = ResultOutput::command(
            c,
            CommandBehavior::FailOnErrorCode,
            CommandCapture::StdoutStderr,
        );
        res_update.clear_ok()
    }

    fn software_upgrade(&mut self, packages: &[PackageSpec]) -> ResultOutput<()> {
        let mut c = Command::new("zypper");
        c.arg("--non-interactive").arg("update");
        c.args(packages.iter().map(Self::package_spec_as_argument));
        let res_update = ResultOutput::command(
            c,
            CommandBehavior::FailOnErrorCode,
            CommandCapture::StdoutStderr,
        );
        res_update.clear_ok()
    }
}

impl LinuxPackageManager for ZypperPackageManager {
    fn update_cache(&mut self) -> ResultOutput<()> {
        let mut c = Command::new("zypper");
        c.arg("--non-interactive").arg("refresh");
        let res_update = ResultOutput::command(
            c,
            CommandBehavior::FailOnErrorCode,
            CommandCapture::StdoutStderr,
        );
        res_update.clear_ok()
    }

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

    fn is_reboot_pending(&self) -> ResultOutput<bool> {
        let mut c = Command::new("zypper");
        c.arg("ps").arg("-s");
        let res = ResultOutput::command(
            c,
            CommandBehavior::FailOnErrorCode,
            CommandCapture::StdoutStderr,
        );

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
        let res = ResultOutput::command(
            c,
            CommandBehavior::FailOnErrorCode,
            CommandCapture::StdoutStderr,
        );
        let (r, o, e) = (res.inner, res.stdout, res.stderr);
        let res = match r {
            Ok(_) => {
                let services = PackageManager::parse_services(&o);
                Ok(services)
            }
            Err(e) => Err(e),
        };
        ResultOutput::new_output(res, o, e)
    }

    fn restart_services(&self) -> ResultOutput<()> {
        todo!()
    }
}
