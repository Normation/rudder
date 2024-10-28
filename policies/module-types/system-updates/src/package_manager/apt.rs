// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

mod filter;
mod progress;

use crate::package_manager::apt::progress::AptAcquireProgress;
use crate::{
    campaign::FullCampaignType,
    output::ResultOutput,
    package_manager::{
        apt::filter::{Distribution, PackageFileFilter},
        LinuxPackageManager, PackageId, PackageInfo, PackageList, PackageManager, PackageSpec,
    },
};
use anyhow::{anyhow, Context, Result};
use memfile::MemFile;
use regex::Regex;
#[cfg(not(debug_assertions))]
use rudder_module_type::ensure_root_user;
use rudder_module_type::os_release::OsRelease;
use rust_apt::{
    cache::Upgrade,
    config::Config,
    error::AptErrors,
    new_cache,
    progress::{AcquireProgress, InstallProgress},
    Cache, PackageSort,
};
use std::{
    collections::HashMap,
    env,
    fs::File,
    io::{Read, Seek},
    path::Path,
    process::Command,
};
use stdio_override::{StdoutOverride, StdoutOverrideGuard};

/// References:
/// * https://www.debian.org/doc/manuals/debian-faq/uptodate.en.html
/// * https://www.debian.org/doc/manuals/debian-handbook/sect.apt-get.en.html#sect.apt-upgrade
/// * https://help.ubuntu.com/community/AutomaticSecurityUpdates
/// * https://www.debian.org/doc/manuals/securing-debian-manual/security-update.en.html
/// * https://wiki.debian.org/UnattendedUpgrades
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
    cache: Option<Cache>,
    distribution: Distribution,
}

impl AptPackageManager {
    pub fn new(os_release: &OsRelease) -> Result<Self> {
        #[cfg(not(debug_assertions))]
        ensure_root_user()?;

        env::set_var("DEBIAN_FRONTEND", "noninteractive");
        // TODO: do we really want to disable list changes?
        // It will be switched to non-interactive mode automatically.
        env::set_var("APT_LISTCHANGES_FRONTEND", "none");
        // We will do this by calling `needrestart` ourselves, turn off the APT hook.
        env::set_var("NEEDRESTART_SUSPEND", "y");

        let cache = new_cache!()?;

        // Used for all package actions
        let dpkg_options = vec!["--force-confold", "--force-confdef"];
        let conf = Config::new();
        conf.set_vector("Dpkg::Options", &dpkg_options);
        // Ensure we log the commandline in apt logs
        conf.set(
            "Commandline::AsString",
            &env::args().collect::<Vec<String>>().join(" "),
        );

        Ok(Self {
            cache: Some(cache),
            distribution: Distribution::new(os_release),
        })
    }

    /// Take the existing cache, or create a new one if it is not available.
    fn cache(&mut self) -> ResultOutput<Cache> {
        if self.cache.is_some() {
            ResultOutput::new(Ok(self.cache.take().unwrap()))
        } else {
            Self::apt_errors_to_output(new_cache!())
        }
    }

    /// Parses the batch output of needrestart.
    /// It conveniently exposes a stable parsing-friendly output.
    ///
    /// https://github.com/liske/needrestart/blob/master/README.batch.md
    pub fn parse_services_to_restart(&self, output: &[String]) -> Result<Vec<String>> {
        let svc_re = Regex::new(r"NEEDRESTART-SVC:\s*(\S+)\s*")?;

        Ok(output
            .iter()
            .flat_map(|line| {
                let service_name = svc_re
                    .captures(line)
                    .map(|cap| cap.get(1).map_or("", |m| m.as_str()).to_string());
                service_name
            })
            .collect())
    }

    fn all_installed() -> PackageSort {
        PackageSort::default().installed().include_virtual()
    }

    /// Converts a list of APT errors to a `ResultOutput`.
    fn apt_errors_to_output<T>(res: Result<T, AptErrors>) -> ResultOutput<T> {
        let mut stderr = vec![];
        let r = match res {
            Ok(o) => Ok(o),
            Err(e) => {
                for error in e.iter() {
                    if error.is_error {
                        stderr.push(format!("Error: {}", error.msg));
                    } else {
                        stderr.push(format!("Warning: {}", error.msg));
                    }
                }
                Err(anyhow!("APT error"))
            }
        };
        ResultOutput::new_output(r, vec![], stderr)
    }

    fn mark_security_upgrades(
        &self,
        cache: &mut Cache,
        security_origins: &[PackageFileFilter],
    ) -> std::result::Result<(), AptErrors> {
        for p in cache.packages(&Self::all_installed()) {
            if p.is_upgradable() {
                for v in p.versions() {
                    if PackageFileFilter::is_in_allowed_origin(&v, &security_origins) {
                        if v > p.installed().unwrap() {
                            v.set_candidate();
                            p.mark_install(true, !p.is_auto_installed());
                            break;
                        }
                    }
                }
            }
        }
        Ok(())
    }

    fn mark_package_upgrades(
        &self,
        packages: &[PackageSpec],
        cache: &mut Cache,
    ) -> std::result::Result<(), AptErrors> {
        for p in packages {
            let package_id = if let Some(ref a) = p.architecture {
                format!("{}:{}", p.name, a)
            } else {
                p.name.clone()
            };
            // Get package from cache
            if let Some(pkg) = cache.get(&package_id) {
                if !pkg.is_installed() {
                    // We only upgrade
                    continue;
                }
                if let Some(ref spec_version) = p.version {
                    let candidate = pkg
                        .versions()
                        .find(|v| v.version() == spec_version.as_str());
                    if let Some(candidate) = candidate {
                        // Don't allow downgrade
                        if candidate > pkg.installed().unwrap() {
                            candidate.set_candidate();
                            pkg.mark_install(true, !pkg.is_auto_installed());
                        }
                    }
                } else {
                    if pkg.is_upgradable() {
                        pkg.mark_install(true, !pkg.is_auto_installed());
                    }
                }
            }
        }
        Ok(())
    }

    fn mark_all_upgrades(&self, cache: &mut Cache) -> std::result::Result<(), AptErrors> {
        // Upgrade type: `apt upgrade` (not `dist-upgrade`).
        // Allows adding packages, but not removing.
        let upgrade_type = Upgrade::Upgrade;
        // Mark all packages for upgrade
        cache.upgrade(upgrade_type)
    }
}

// Catch stdout/stderr from the library
pub struct OutputCatcher {
    out_file: File,
    out_guard: StdoutOverrideGuard,
}

impl OutputCatcher {
    pub fn new() -> Self {
        let out_file = MemFile::create_default("stdout").unwrap().into_file();
        let out_guard = StdoutOverride::override_raw(out_file.try_clone().unwrap()).unwrap();
        Self {
            out_file,
            out_guard,
        }
    }

    pub fn read(mut self) -> String {
        drop(self.out_guard);
        let mut out = String::new();
        self.out_file.rewind().unwrap();
        self.out_file.read_to_string(&mut out).unwrap();
        out
    }
}

impl LinuxPackageManager for AptPackageManager {
    fn update_cache(&mut self) -> ResultOutput<()> {
        let cache = self.cache();

        if let Ok(o) = cache.inner {
            let mut progress = AcquireProgress::new(AptAcquireProgress::new());
            let catch = OutputCatcher::new();
            let mut r = Self::apt_errors_to_output(o.update(&mut progress));
            let out = catch.read();
            r.stdout(out);
            r
        } else {
            cache.clear_ok()
        }
    }

    fn list_installed(&mut self) -> ResultOutput<PackageList> {
        let cache = self.cache();

        let step = if let Ok(ref c) = cache.inner {
            let filter = Self::all_installed();

            let mut list = HashMap::new();
            for p in c.packages(&filter) {
                let v = p.installed().expect("Only installed packages are listed");
                let info = PackageInfo {
                    version: v.version().to_string(),
                    from: v.source_name().to_string(),
                    source: PackageManager::Apt,
                };
                let id = PackageId {
                    name: p.name().to_string(),
                    arch: p.arch().to_string(),
                };

                list.insert(id, info);
            }
            ResultOutput::new(Ok(PackageList::new(list)))
        } else {
            ResultOutput::new(Err(anyhow!("Error listing installed packages")))
        };

        // FIXME keep cache?
        cache.step(step)
    }

    fn upgrade(&mut self, update_type: &FullCampaignType) -> ResultOutput<()> {
        let cache = self.cache();
        if let Ok(mut c) = cache.inner {
            //if c.get_changes(false).peekable().next().is_some() {
            //    c.clear()
            //}

            let mark_res = AptPackageManager::apt_errors_to_output(match update_type {
                FullCampaignType::SystemUpdate => self.mark_all_upgrades(&mut c),
                FullCampaignType::SecurityUpdate => {
                    let security_origins = match self.distribution.security_origins() {
                        Ok(origins) => origins,
                        // Fail loudly if not supported
                        Err(e) => return ResultOutput::new(Err(e)),
                    };
                    self.mark_security_upgrades(&mut c, &security_origins)
                }
                FullCampaignType::SoftwareUpdate(p) => self.mark_package_upgrades(p, &mut c),
            });
            if mark_res.inner.is_err() {
                return mark_res;
            }

            // Resolve dependencies
            let res_resolve = Self::apt_errors_to_output(c.resolve(true));
            if res_resolve.inner.is_err() {
                return res_resolve;
            }

            // Do the changes
            let mut acquire_progress = AcquireProgress::new(AptAcquireProgress::new());
            let mut install_progress = InstallProgress::default();
            let catch = OutputCatcher::new();
            let mut res_commit =
                Self::apt_errors_to_output(c.commit(&mut acquire_progress, &mut install_progress));
            let out = catch.read();
            res_commit.stdout(out);
            res_resolve.step(res_commit)
        } else {
            cache.clear_ok()
        }
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
            Ok(_) => self.parse_services_to_restart(&o),
            Err(e) => Err(e).context("Checking services to restart with the needrestart command"),
        };
        ResultOutput::new_output(res, o, e)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_services_to_restart() {
        let os_release = OsRelease::from_string("");
        let apt = AptPackageManager::new(&os_release).unwrap();

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
        let lines1: Vec<String> = output1.lines().map(|s| s.to_string()).collect();
        assert_eq!(
            apt.parse_services_to_restart(lines1.as_slice()).unwrap(),
            expected1
        );

        // Test with an empty string
        let output2 = "";
        let expected2: Vec<String> = Vec::new();
        let lines2: Vec<String> = output2.lines().map(|s| s.to_string()).collect();
        assert_eq!(
            apt.parse_services_to_restart(lines2.as_slice()).unwrap(),
            expected2
        );
    }

    #[test]
    // Needs "-- --nocapture --ignored" to run in tests as cargo test also messes with stdout/stderr
    #[ignore]
    fn it_captures_stdout() {
        let catch = OutputCatcher::new();
        println!("plouf");
        let out = catch.read();
        assert_eq!(out, "plouf\n".to_string());
    }
}
