// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use std::{env, io::BufRead, path::Path, process::Command};

use anyhow::{bail, Result};
use regex::Regex;

use crate::{
    output::ResultOutput,
    package_manager::{
        dpkg::DpkgPackageManager, rpm::RpmPackageManager, LinuxPackageManager, PackageDiff,
        PackageList, PackageSpec,
    },
};
// FIXME install needrestart in the technique!

// FIXME : list compatible OS

const NEED_RESTART_PATH: &str = "/usr/bin/needs-restarting";
const REBOOT_REQUIRED_FILE_PATH: &str = "/var/run/reboot-required";

#[derive(Debug, PartialEq, Eq, PartialOrd, Ord, Clone, Copy)]
pub enum AptCompatibility {
    /// < 1.0
    Zero,
    /// >= 1.0
    OneZero,
}

pub struct AptPackageManager {
    compat: AptCompatibility,
    dpkg: DpkgPackageManager,
}

// --force-confold: do not modify the current configuration file, the new version is installed with a .dpkg-dist suffix. With this option alone, even configuration files that you have not modified are left untouched. You need to combine it with --force-confdef to let dpkg overwrite configuration files that you have not modified.
// --force-confnew: always install the new version of the configuration file, the current version is kept in a file with the .dpkg-old suffix.
// --force-confdef: ask dpkg to decide alone when it can and prompt otherwise. This is the default behavior of dpkg and this option is mainly useful in combination with --force-confold.
// --force-confmiss: ask dpkg to install the configuration file if it’s currently missing (for example because you have removed the file by mistake).

impl AptPackageManager {
    pub fn new() -> Result<Self> {
        env::set_var("DEBIAN_FRONTEND", "noninteractive");
        // TODO: do we really want to disable list changes.
        // It will be switched to non-interactive mode automatically.
        env::set_var("APT_LISTCHANGES_FRONTEND", "none");
        // We will do this by calling `needrestart` ourselves, turn off the APT hook.
        env::set_var("NEEDRESTART_SUSPEND", "y");

        let o = Command::new("apt-get").arg("--version").output()?;

        // FIXME unwrap
        let line = o.stdout.lines().next().unwrap()?;
        let v = line.split(' ').nth(1).unwrap();
        let compat = if v.starts_with('0') {
            AptCompatibility::Zero
        } else {
            AptCompatibility::OneZero
        };

        let dpkg = DpkgPackageManager::new();
        Ok(Self { compat, dpkg })
    }

    // apt install foo:i386=1.7.13+ds-2ubuntu1
    fn package_spec_as_argument(p: PackageSpec) -> String {
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

    /// Parses the batch output of needrestart.
    ///
    /// https://github.com/liske/needrestart/blob/master/README.batch.md
    pub fn parse_services_to_restart(&self, output: &str) -> Result<Vec<String>> {
        const SVC_RE: &str = r"NEEDRESTART-SVC:\s*(\S+)\s*";
        let re = Regex::new(SVC_RE).unwrap();

        Ok(output
            .lines()
            .flat_map(|line| {
                let service_name = if let Some(cap) = re.captures(line) {
                    Some(cap.get(1).map_or("", |m| m.as_str()).to_string())
                } else {
                    None
                };
                service_name
            })
            .collect())
    }

    fn update(&self) -> ResultOutput<()> {
        let mut res = ResultOutput::new(Ok(()));
        let mut c = Command::new("apt-get");
        c.arg("--yes").arg("update");
        let _ = res.command(c);
        res
    }
}

impl LinuxPackageManager for AptPackageManager {
    fn list_installed(&self) -> Result<PackageList> {
        self.dpkg.installed()
    }

    fn full_upgrade(&self) -> ResultOutput<()> {
        let mut res = ResultOutput::new(Ok(()));

        // https://superuser.com/questions/1412054/non-interactive-apt-upgrade
        let mut c = Command::new("apt-get");
        c.arg("--yes")
            .arg("--quiet")
            .args(["-o", "Dpkg::Options::=--force-confold"])
            .args(["-o", "Dpkg::Options::=--force-confdef"])
            .arg("dist-upgrade");
        let _ = res.command(c);
        res
    }

    fn security_upgrade(&self) -> ResultOutput<()> {
        // tricky
        todo!()
    }

    fn upgrade(&self, packages: Vec<PackageSpec>) -> ResultOutput<()> {
        let mut res = ResultOutput::new(Ok(()));
        // https://superuser.com/questions/1412054/non-interactive-apt-upgrade
        let mut c = Command::new("apt-get");
        c.arg("--yes")
            .arg("--quiet")
            .args(["-o", "Dpkg::Options::=--force-confold"])
            .args(["-o", "Dpkg::Options::=--force-confdef"])
            .arg("install");

        if self.compat == AptCompatibility::OneZero {
            c.arg("--with-new-pkgs");
        }

        c.args(
            packages
                .into_iter()
                .map(|p| Self::package_spec_as_argument(p)),
        );

        let _ = res.command(c);
        res
    }

    fn reboot_pending(&self) -> Result<bool> {
        // `needrestart` doesn't bring anything more here. It only covers kernel-based required reboots,
        // which are already covered with this file.
        Ok(Path::new(REBOOT_REQUIRED_FILE_PATH).exists())
    }

    fn services_to_restart(&self) -> Result<Vec<String>> {
        let o = Command::new("needrestart")
            // list only
            .arg("-r")
            .arg("l")
            // batch mode (parsable output)
            .arg("-b")
            .output()?;
        if !o.status.success() {
            bail!("TODO");
        }
        let o = String::from_utf8_lossy(&o.stdout);
        self.parse_services_to_restart(&o)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_services_to_restart() {
        let apt = AptPackageManager {
            compat: AptCompatibility::OneZero,
            dpkg: DpkgPackageManager::new(),
        };

        let output1 = "NEEDRESTART-VER: 3.6
NEEDRESTART-KCUR: 6.1.0-20-amd64
NEEDRESTART-KEXP: 6.1.0-22-amd64
NEEDRESTART-KSTA: 3
NEEDRESTART-SVC: apache2.service
NEEDRESTART-SVC: cron.service
NEEDRESTART-SVC: getty@tty1.service
NEEDRESTART-SESS: amousset @ session #54207
NEEDRESTART-SESS: amousset @ user manager service";
        let expected1 = vec!["apache2.service", "cron.service", "getty@tty1.service"];
        assert_eq!(apt.parse_services_to_restart(output1).unwrap(), expected1);

        // Test with an empty string
        let output2 = "";
        let expected2: Vec<String> = Vec::new();
        assert_eq!(apt.parse_services_to_restart(output2).unwrap(), expected2);
    }
}
