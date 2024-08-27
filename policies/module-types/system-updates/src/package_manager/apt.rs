// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use anyhow::{anyhow, Context, Result};
use regex::Regex;
use std::{collections::HashMap, env, path::Path, process::Command};

use crate::{
    output::ResultOutput,
    package_manager::{
        LinuxPackageManager, PackageId, PackageInfo, PackageList, PackageManager, PackageSpec,
    },
};
use rust_apt::{
    cache::Upgrade,
    config::Config,
    error::AptErrors,
    new_cache,
    progress::{AcquireProgress, InstallProgress},
    PackageSort,
};

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
        // FIXME: do we need this with the lib?
        env::set_var("DEBIAN_FRONTEND", "noninteractive");
        // FIXME: do we really want to disable list changes.
        // It will be switched to non-interactive mode automatically.
        env::set_var("APT_LISTCHANGES_FRONTEND", "none");
        // We will do this by calling `needrestart` ourselves, turn off the APT hook.
        env::set_var("NEEDRESTART_SUSPEND", "y");

        let cache = new_cache!()?;

        // Used for all package actions
        let dpkg_options = vec!["--force-confold", "--force-confdef"];
        let conf = Config::new();
        conf.set_vector("Dpkg::Options", &dpkg_options);

        Ok(Self { cache: Some(cache) })
    }

    /// Take the existing cache, or create a new one if it is not available.
    fn cache(&mut self) -> ResultOutput<rust_apt::Cache> {
        Ok(if self.cache.is_some() {
            ResultOutput::new(Ok(self.cache.take().unwrap()))
        } else {
            let r = new_cache!();
            match r {
                Ok(c) => ResultOutput::new(Ok(c)),
                Err(e) => Self::apt_errors_to_output(Err(e)),
            }
        })
    }

    /// Parses the batch output of needrestart.
    /// It conveniently exposes a stable parsing-friendly output.
    ///
    /// https://github.com/liske/needrestart/blob/master/README.batch.md
    pub fn parse_services_to_restart(&self, output: &str) -> Result<Vec<String>> {
        let svc_re = Regex::new(r"NEEDRESTART-SVC:\s*(\S+)\s*")?;

        Ok(output
            .lines()
            .flat_map(|line| {
                let service_name = svc_re
                    .captures(line)
                    .map(|cap| cap.get(1).map_or("", |m| m.as_str()).to_string());
                service_name
            })
            .collect())
    }

    fn update(&mut self) -> ResultOutput<()> {
        let cache = self.cache().unwrap();

        let mut progress = AcquireProgress::apt();
        Self::apt_errors_to_output(cache.update(&mut progress))
    }

    fn apt_errors_to_output<T>(res: Result<T, AptErrors>) -> ResultOutput<()> {
        match res {
            Ok(_) => ResultOutput::new(Ok(())),
            Err(e) => {
                let mut res = ResultOutput::new(Ok(()));
                let mut has_error = false;

                for error in e.iter() {
                    if error.is_error {
                        has_error = true;
                        res.stderr(format!("Error: {}", error.msg));
                    } else {
                        res.stderr(format!("Warning: {}", error.msg));
                    }
                }

                if has_error {
                    res.inner = Err(anyhow!("APT errors"));
                }
                res
            }
        }
    }
}

impl LinuxPackageManager for AptPackageManager {
    fn list_installed(&mut self) -> ResultOutput<PackageList> {
        let cache = self.cache();
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
                arch: p.arch().to_string(),
            };

            list.insert(id, info);
        }
        self.cache = Some(cache);
        Ok(PackageList::new(list))
    }

    fn full_upgrade(&mut self) -> ResultOutput<()> {
        let cache = self.cache().unwrap();

        // Upgrade type: `apt upgrade` (not `dist-upgrade`).
        // Allows adding packages, but not removing.
        // FIXME: release notes, we did a dist-upgrade before
        let upgrade_type = Upgrade::Upgrade;

        // Mark all packages for upgrade
        let res = Self::apt_errors_to_output(cache.upgrade(upgrade_type));

        // Resolve dependencies
        cache.resolve(true);

        // Do the changes
        let mut install_progress = InstallProgress::apt();
        let mut acquire_progress = AcquireProgress::apt();
        cache.commit(&mut acquire_progress, &mut install_progress);

        res
    }

    fn security_upgrade(&mut self) -> ResultOutput<()> {
        // This is tricky, there is nothing built-in in apt CLI. The only official way to do this is to use `unattended-upgrades`.
        // We try to copy the logic from `unattended-upgrades` here.
        //
        // https://help.ubuntu.com/community/AutomaticSecurityUpdates
        // https://www.debian.org/doc/manuals/securing-debian-manual/security-update.en.html
        // https://wiki.debian.org/UnattendedUpgrades

        let cache = self.cache().unwrap();
        let filter = PackageSort::default().installed().include_virtual();

        for p in cache.packages(&filter) {
            p.versions().for_each(|v| {
                // FIXME: get actual default rules from unnatended upgrades
                if v.source_name().contains("security") {
                    v.set_candidate();
                    p.mark_install(false, false);
                }
            });
        }

        // Resolve dependencies
        cache.resolve(true);

        // Do the changes
        let mut acquire_progress = AcquireProgress::apt();
        let mut install_progress = InstallProgress::apt();
        Self::apt_errors_to_output(cache.commit(&mut acquire_progress, &mut install_progress))
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

            if let Some(spec_version) = p.version {
                let candidate = pkg
                    .versions()
                    .find(|v| v.version() == spec_version.as_str());
                if let Some(candidate) = candidate {
                    candidate.set_candidate();
                    // FIXME check result, error if not found, but still do the others
                    pkg.mark_install(false, false);
                }
            } else {
                // FIXME check marking because it's not clear
                pkg.mark_install(false, false);
            }
        }

        // Resolve dependencies
        cache.resolve(true);

        // Do the changes
        let mut acquire_progress = AcquireProgress::apt();
        let mut install_progress = InstallProgress::apt();
        Self::apt_errors_to_output(cache.commit(&mut acquire_progress, &mut install_progress))
    }

    fn reboot_pending(&self) -> ResultOutput<bool> {
        // The `needrestart` command doesn't bring anything more here.
        // It only covers kernel-based required reboots, which are also covered with this file.
        let pending_reboot = Path::new(REBOOT_REQUIRED_FILE_PATH)
            .try_exists()
            .context(format!(
                "Checking if a reboot is pending by checking for '{}' existence",
                REBOOT_REQUIRED_FILE_PATH
            ));
        ResultOutput::new(pending_reboot)
    }

    fn services_to_restart(&self) -> ResultOutput<Vec<String>> {
        let mut c = Command::new("needrestart");
        c
            // list only
            .arg("-r")
            .arg("l")
            // batch mode (parsable output)
            .arg("-b");
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
