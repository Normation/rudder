// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

mod filter;
mod progress;

use crate::output::{CommandBehavior, CommandCapture};
use crate::{
    campaign::FullCampaignType,
    output::ResultOutput,
    package_manager::{
        PackageId, PackageInfo, PackageList, PackageManager, PackageSpec, UpdateManager,
        apt::{
            filter::{Distribution, PackageFileFilter},
            progress::RudderAptAcquireProgress,
        },
    },
};
use anyhow::{Context, Result, anyhow};
use gag::Gag;
use log::debug;
use memfile::MemFile;
use regex::Regex;
#[cfg(not(debug_assertions))]
use rudder_module_type::ensure_root_user;
use rudder_module_type::os_release::OsRelease;

#[cfg(feature = "apt-compat")]
use rust_apt_compat as rust_apt;

use rust_apt::{
    Cache, PackageSort,
    cache::Upgrade,
    config::Config,
    error::AptErrors,
    new_cache,
    progress::{AcquireProgress, InstallProgress},
};
use std::{collections::HashMap, env, os::fd::AsRawFd, path::Path, process::Command};

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

        // SAFETY: single-threaded
        unsafe {
            env::set_var("DEBIAN_FRONTEND", "noninteractive");
            // TODO: do we really want to disable list changes?
            // It will be switched to non-interactive mode automatically.
            env::set_var("APT_LISTCHANGES_FRONTEND", "none");
            // We will do this by calling `needrestart` ourselves, turn off the APT hook.
            env::set_var("NEEDRESTART_SUSPEND", "y");
        }

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
    pub fn parse_services_to_restart(output: &[String]) -> Result<Vec<String>> {
        let svc_re = Regex::new(r"NEEDRESTART-SVC:\s*(\S+)\s*")?;

        Ok(output
            .iter()
            .flat_map(|s| s.lines())
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
                    debug!("Considering version: {:?}", &v);
                    if PackageFileFilter::is_in_allowed_origins(&v, &security_origins) {
                        if v > p.installed().unwrap() {
                            debug!("Marking version for upgrade");
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

impl UpdateManager for AptPackageManager {
    fn update_cache(&mut self) -> ResultOutput<()> {
        let cache = self.cache();

        if let Ok(o) = cache.inner {
            let mem_file_acquire = MemFile::create_default("update-acquire").unwrap();
            let mut progress = AcquireProgress::new(RudderAptAcquireProgress::new(
                mem_file_acquire.try_clone().unwrap(),
            ));
            let mut r = Self::apt_errors_to_output(o.update(&mut progress));

            let acquire_out = RudderAptAcquireProgress::read_mem_file(mem_file_acquire);
            r.stdout(acquire_out);
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
                    details: None,
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

    fn upgrade(
        &mut self,
        update_type: &FullCampaignType,
    ) -> ResultOutput<Option<HashMap<PackageId, String>>> {
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
                    debug!("Allowed origins: {:?}", security_origins);
                    self.mark_security_upgrades(&mut c, &security_origins)
                }
                FullCampaignType::SoftwareUpdate(p) => self.mark_package_upgrades(p, &mut c),
            });
            if let Err(e) = mark_res.inner {
                return ResultOutput {
                    inner: Err(e),
                    stdout: mark_res.stdout,
                    stderr: mark_res.stderr,
                };
            }

            // Resolve dependencies
            let res_resolve = Self::apt_errors_to_output(c.resolve(true));
            if let Err(e) = res_resolve.inner {
                return ResultOutput {
                    inner: Err(e),
                    stdout: mark_res.stdout,
                    stderr: mark_res.stderr,
                };
            }

            // Do the changes
            let mem_file_acquire = MemFile::create_default("upgrade-acquire").unwrap();
            let mut acquire_progress = AcquireProgress::new(RudderAptAcquireProgress::new(
                mem_file_acquire.try_clone().unwrap(),
            ));

            let mem_file_install = MemFile::create_default("upgrade-install").unwrap();
            let mut install_progress = InstallProgress::fd(mem_file_install.as_raw_fd());

            // Before making calls to the package manager, we need to silence stdout.
            // APT writes on it, and it breaks CFEngine's protocol.
            let print_gag_out = Gag::stdout().expect("Failed to silence stdout");
            let mut res_commit =
                Self::apt_errors_to_output(c.commit(&mut acquire_progress, &mut install_progress));
            drop(print_gag_out);

            let acquire_out = RudderAptAcquireProgress::read_mem_file(mem_file_acquire);
            res_commit.stdout(acquire_out);

            let install_out = RudderAptAcquireProgress::read_mem_file(mem_file_install);
            res_commit.stdout(install_out);

            let mut ro = ResultOutput {
                inner: Ok(None),
                stdout: vec![],
                stderr: vec![],
            };
            ro.log_step(&res_resolve);
            ro.log_step(&res_commit);
            ro
        } else {
            cache.clear_ok_with_details()
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
        let res = ResultOutput::command(
            c,
            CommandBehavior::FailOnErrorCode,
            CommandCapture::StdoutStderr,
        );
        let (r, o, e) = (res.inner, res.stdout, res.stderr);
        let res = match r {
            Ok(_) => Self::parse_services_to_restart(&o),
            Err(e) => Err(e).context("Checking services to restart with the needrestart command"),
        };
        ResultOutput::new_output(res, o, e)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_services_to_restart_multi_string() {
        let output = "NEEDRESTART-VER: 3.6
NEEDRESTART-KCUR: 6.1.0-20-amd64
NEEDRESTART-KEXP: 6.1.0-22-amd64
NEEDRESTART-KSTA: 3
NEEDRESTART-SVC: apache2.service
NEEDRESTART-SVC: cron.service
NEEDRESTART-SVC: getty@tty1.service
NEEDRESTART-SESS: amousset @ session #54207
NEEDRESTART-SESS: amousset @ user manager service";
        let expected = vec!["apache2.service", "cron.service", "getty@tty1.service"];
        let lines: Vec<String> = output.lines().map(|s| s.to_string()).collect();
        assert_eq!(
            AptPackageManager::parse_services_to_restart(lines.as_slice()).unwrap(),
            expected
        );
    }

    #[test]
    fn test_parse_services_to_restart_empty_string() {
        let output = "";
        let expected: Vec<String> = Vec::new();
        let lines: Vec<String> = output.lines().map(|s| s.to_string()).collect();
        assert_eq!(
            AptPackageManager::parse_services_to_restart(lines.as_slice()).unwrap(),
            expected
        );
    }

    #[test]
    fn test_parse_services_to_restart_mono_string() {
        // Single string output
        let output = "NEEDRESTART-VER: 3.6
NEEDRESTART-KCUR: 6.1.0-20-amd64
NEEDRESTART-KEXP: 6.1.0-22-amd64
NEEDRESTART-KSTA: 3
NEEDRESTART-SVC: apache2.service
NEEDRESTART-SVC: cron.service
NEEDRESTART-SVC: getty@tty1.service
NEEDRESTART-SESS: amousset @ session #54207
NEEDRESTART-SESS: amousset @ user manager service";
        let expected = vec!["apache2.service", "cron.service", "getty@tty1.service"];
        let lines: Vec<String> = vec![output.to_string()];
        assert_eq!(
            AptPackageManager::parse_services_to_restart(lines.as_slice()).unwrap(),
            expected
        );
    }
}
