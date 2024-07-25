// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use anyhow::{bail, Result};
use regex::Regex;
use std::collections::HashMap;
use std::{env, path::Path, process::Command};

use crate::package_manager::{PackageId, PackageInfo, PackageManager};
use crate::{
    output::ResultOutput,
    package_manager::{LinuxPackageManager, PackageList, PackageSpec},
};
use rust_apt::cache::Upgrade;
use rust_apt::config::Config;
use rust_apt::progress::{AcquireProgress, InstallProgress};
use rust_apt::{new_cache, PackageSort};

/// Reference guides:
/// * https://www.debian.org/doc/manuals/debian-faq/uptodate.en.html
/// * https://www.debian.org/doc/manuals/debian-handbook/sect.apt-get.en.html#sect.apt-upgrade
///
/// Our reference model is unattended-upgrades, which is the only “official” way to handle automatic upgrades.
/// We need to be compatible with:
///
/// * Ubuntu LTS 18.04+ (APT 1.6.1)
/// * Debian 10+ (APT 1.8.2.3)
///
/// Requires `libapt-pkg` installed (and `libapt-pkg-dev` for build).
///
/// The main drawback of using the library is that it doesn't provide a way to run equivalent commands
/// directly for debugging, but it helps to avoid parsing the output of the command and to manage the errors.

const REBOOT_REQUIRED_FILE_PATH: &str = "/var/run/reboot-required";

pub struct AptPackageManager {
    /// Use an `Option` as some methods will consume the cache.
    cache: Option<rust_apt::Cache>,
}

impl AptPackageManager {
    pub fn new() -> Result<Self> {
        env::set_var("DEBIAN_FRONTEND", "noninteractive");
        // FIXME: do we really want to disable list changes.
        // It will be switched to non-interactive mode automatically.
        env::set_var("APT_LISTCHANGES_FRONTEND", "none");
        // We will do this by calling `needrestart` ourselves, turn off the APT hook.
        env::set_var("NEEDRESTART_SUSPEND", "y");

        let cache = new_cache!()?;
        // Equivalent to `apt upgrade`, allow adding packages, but not removing.
        // FIXME: release notes, we did a dist-upgrade before

        // Used for all package actions
        let dpkg_options = vec!["--force-confold", "--force-confdef"];
        let conf = Config::new();
        conf.set_vector("Dpkg::Options", &dpkg_options);

        Ok(Self { cache: Some(cache) })
    }

    /// Take the existing cache, or create a new one if it is not available.
    fn cache(&mut self) -> Result<rust_apt::Cache> {
        Ok(if self.cache.is_some() {
            self.cache.take().unwrap()
        } else {
            new_cache!()?
        })
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
                let service_name = re
                    .captures(line)
                    .map(|cap| cap.get(1).map_or("", |m| m.as_str()).to_string());
                service_name
            })
            .collect())
    }

    fn update(&mut self) -> ResultOutput<()> {
        let cache = self.cache().unwrap();

        let mut progress = AcquireProgress::apt();
        if let Err(e) = cache.update(&mut progress) {
            for error in e.iter() {
                if error.is_error {
                    println!("Error: {}", error.msg);
                } else {
                    println!("Warning: {}", error.msg);
                }
            }
        }

        ResultOutput::new(Ok(()))
    }
}

impl LinuxPackageManager for AptPackageManager {
    fn list_installed(&mut self) -> Result<PackageList> {
        let cache = self.cache().unwrap();
        // FIXME: compare with dpkg output
        let filter = PackageSort::default().installed().include_virtual();

        let mut list = HashMap::new();
        for p in cache.packages(&filter) {
            let v = p.installed().expect("Only installed packages are listed");
            let info = PackageInfo {
                version: v.version().to_string(),
                from: "FIXME".to_string(),
                source: PackageManager::Apt,
            };
            let id = PackageId {
                name: p.name().to_string(),
                arch: p.().to_string(),
            };

            list.insert(id, info);
        }
        self.cache = Some(cache);
        Ok(PackageList::new(list))
    }

    fn full_upgrade(&mut self) -> ResultOutput<()> {
        let cache = self.cache().unwrap();

        let upgrade_type = Upgrade::Upgrade;
        let res = cache.upgrade(upgrade_type);

        ResultOutput::new(Ok(()))
    }

    fn security_upgrade(&mut self) -> ResultOutput<()> {
        // This is tricky, there is nothing built-in in apt. The only somehow official way to do this is to use `unattended-upgrades`.
        // We try to copy the logic from `unattended-upgrades` here.
        //
        // https://help.ubuntu.com/community/AutomaticSecurityUpdates
        // https://www.debian.org/doc/manuals/securing-debian-manual/security-update.en.html
        // https://wiki.debian.org/UnattendedUpgrades

        todo!("security_upgrade")
    }

    fn upgrade(&mut self, packages: Vec<PackageSpec>) -> ResultOutput<()> {
        let cache = self.cache().unwrap();

        for p in packages {
            let package_id = if let Some(a) = p.architecture {
                format!("{}:{}", p.name, a)
            } else {
                p.name
            };
            // Get package from cache
            // FIXME: handle absent package
            let pkg = cache.get(&package_id).unwrap();

            if let Some(v) = p.version {
                todo!()
            } else {
                // FIXME check marking because it's not clear
                pkg.mark_install(false, false);
            }
        }

        let mut acquire_progress = AcquireProgress::apt();
        let mut install_progress = InstallProgress::apt();
        cache.commit(&mut acquire_progress, &mut install_progress);

        ResultOutput::new(Ok(()))
    }

    fn reboot_pending(&self) -> Result<bool> {
        // The `needrestart` command doesn't bring anything more here.
        // It only covers kernel-based required reboots, which are also covered with this file.
        Ok(Path::new(REBOOT_REQUIRED_FILE_PATH).try_exists()?)
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
        let apt = AptPackageManager::new().unwrap();

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
