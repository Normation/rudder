// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use std::collections::HashMap;
use std::process::Command;

use anyhow::Result;

use crate::output::{CommandBehavior, CommandCapture};
use crate::package_manager::{PackageId, PackageManager};
use crate::{
    campaign::FullCampaignType,
    output::ResultOutput,
    package_manager::{PackageList, PackageSpec, UpdateManager, rpm::RpmPackageManager},
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

    fn package_specs_as_exclude_argument(p: &[PackageSpec]) -> Vec<String> {
        p.iter()
            .map(|p| format!("--exclude={}", Self::package_spec_as_argument(p)))
            .collect::<Vec<_>>()
    }

    fn full_upgrade(
        &mut self,
        excludes: &[PackageSpec],
    ) -> ResultOutput<Option<HashMap<PackageId, String>>> {
        // https://serverfault.com/a/1075175
        let mut c = Command::new("yum");
        c.arg("--assumeyes")
            .args(Self::package_specs_as_exclude_argument(excludes))
            .arg("update");
        ResultOutput::command(
            c,
            CommandBehavior::FailOnErrorCode,
            CommandCapture::StdoutStderr,
        )
        .clear_ok_with_details()
    }

    /// `yum install yum-plugin-security` is only necessary on RHEL < 7, which are not supported.
    fn security_upgrade(
        &mut self,
        excludes: &[PackageSpec],
    ) -> ResultOutput<Option<HashMap<PackageId, String>>> {
        // See https://access.redhat.com/solutions/10021
        let mut c = Command::new("yum");
        c.arg("--assumeyes")
            .arg("--security")
            .args(Self::package_specs_as_exclude_argument(excludes))
            .arg("update");
        ResultOutput::command(
            c,
            CommandBehavior::FailOnErrorCode,
            CommandCapture::StdoutStderr,
        )
        .clear_ok_with_details()
    }

    fn software_upgrade(
        &mut self,
        packages: &[PackageSpec],
        excludes: &[PackageSpec],
    ) -> ResultOutput<Option<HashMap<PackageId, String>>> {
        let mut c = Command::new("yum");
        c.arg("--assumeyes")
            .args(Self::package_specs_as_exclude_argument(excludes))
            .arg("update")
            .args(packages.iter().map(Self::package_spec_as_argument));
        ResultOutput::command(
            c,
            CommandBehavior::FailOnErrorCode,
            CommandCapture::StdoutStderr,
        )
        .clear_ok_with_details()
    }
}

impl UpdateManager for YumPackageManager {
    fn list_installed(&mut self) -> ResultOutput<PackageList> {
        self.rpm.installed()
    }

    fn upgrade(
        &mut self,
        update_type: &FullCampaignType,
    ) -> ResultOutput<Option<HashMap<PackageId, String>>> {
        match update_type {
            FullCampaignType::SystemUpdate(e) => self.full_upgrade(e),
            FullCampaignType::SecurityUpdate(e) => self.security_upgrade(e),
            FullCampaignType::SoftwareUpdate(p, e) => self.software_upgrade(p, e),
        }
    }

    fn reboot_pending(&self) -> ResultOutput<bool> {
        let mut c = Command::new("needs-restarting");
        c.arg("--reboothint");
        let res = ResultOutput::command(
            c,
            CommandBehavior::OkOnErrorCode,
            CommandCapture::StdoutStderr,
        );

        let (r, o, e) = (res.inner, res.stdout, res.stderr);
        let res = match r {
            // report whether a reboot is required (exit code 1) or not (exit code 0)
            Ok(o) => Ok(!o.status.success()),
            Err(e) => Err(e),
        };
        ResultOutput::new_output(res, o, e)
    }

    fn services_to_restart(&self) -> ResultOutput<Vec<String>> {
        let mut c = Command::new("needs-restarting");
        c.arg("--services");
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
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::package_manager::PackageSpec;

    #[test]
    fn test_package_spec_as_argument() {
        let p = PackageSpec {
            name: "foo".to_string(),
            version: Some("1.0".to_string()),
            architecture: Some("x86_64".to_string()),
        };
        assert_eq!(
            YumPackageManager::package_spec_as_argument(&p),
            "foo-1.0.x86_64"
        );
    }

    #[test]
    fn test_package_specs_as_exclude_argument() {
        let specs = vec![
            PackageSpec {
                name: "foo".to_string(),
                version: Some("1.0".to_string()),
                architecture: Some("x86_64".to_string()),
            },
            PackageSpec {
                name: "bar".to_string(),
                version: None,
                architecture: None,
            },
        ];
        let expected = vec![
            "--exclude=foo-1.0.x86_64".to_string(),
            "--exclude=bar".to_string(),
        ];
        assert_eq!(
            YumPackageManager::package_specs_as_exclude_argument(&specs),
            expected
        );
    }
}
