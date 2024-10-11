// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use crate::{
    output::ResultOutput,
    package_manager::{
        LinuxPackageManager, PackageId, PackageInfo, PackageList, PackageManager, PackageSpec,
    },
};
use anyhow::{anyhow, Context, Result};
use memfile::MemFile;
use regex::Regex;
use rust_apt::{
    cache::Upgrade,
    config::Config,
    error::AptErrors,
    new_cache,
    progress::{AcquireProgress, InstallProgress},
    PackageSort,
};
use std::{collections::HashMap, env, io::Read, path::Path, process::Command};
use stdio_override::{StderrOverride, StderrOverrideGuard, StdoutOverride, StdoutOverrideGuard};

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
        // TODO: do we need this with the lib?
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

        Ok(Self { cache: Some(cache) })
    }

    /// Take the existing cache, or create a new one if it is not available.
    fn cache(&mut self) -> ResultOutput<rust_apt::Cache> {
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
                Err(anyhow!("Apt error"))
            }
        };
        ResultOutput::new_output(r, vec![], stderr)
    }
}

// Catch stdout/stderr from the library
pub struct OutputCatcher {
    out_file: MemFile,
    out_guard: StdoutOverrideGuard,
    err_file: MemFile,
    err_guard: StderrOverrideGuard,
}

impl OutputCatcher {
    pub fn new() -> Self {
        let out_file = MemFile::create_default("stdout").unwrap();
        let err_file = MemFile::create_default("stderr").unwrap();
        let out_guard = StdoutOverride::override_raw(out_file.try_clone().unwrap()).unwrap();
        let err_guard = StderrOverride::override_raw(err_file.try_clone().unwrap()).unwrap();

        Self {
            out_file,
            out_guard,
            err_file,
            err_guard,
        }
    }

    pub fn read(mut self) -> (String, String) {
        drop(self.out_guard);
        drop(self.err_guard);
        let mut out = String::new();
        self.out_file.read_to_string(&mut out).unwrap();
        let mut err = String::new();
        self.err_file.read_to_string(&mut err).unwrap();
        (out, err)
    }
}

impl LinuxPackageManager for AptPackageManager {
    fn update_cache(&mut self) -> ResultOutput<()> {
        let cache = self.cache();

        if let Ok(o) = cache.inner {
            let mut progress = AcquireProgress::apt();

            // Collect stdout through an in-memory fd.
            let catch = OutputCatcher::new();
            let mut r = Self::apt_errors_to_output(o.update(&mut progress));
            let (out, err) = catch.read();
            // FIXME should be inserted before
            r.stdout(out);
            r.stderr(err);
            r
        } else {
            cache.clear_ok()
        }
    }

    fn list_installed(&mut self) -> ResultOutput<PackageList> {
        let cache = self.cache();

        let step = if let Ok(ref c) = cache.inner {
            // FIXME: compare with dpkg output
            let filter = PackageSort::default().installed().include_virtual();

            let mut list = HashMap::new();
            for p in c.packages(&filter) {
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
            ResultOutput::new(Ok(PackageList::new(list)))
        } else {
            ResultOutput::new(Err(anyhow!("Error listing installed packages")))
        };

        // FIXME keep cache?
        cache.step(step)
    }

    fn full_upgrade(&mut self) -> ResultOutput<()> {
        let cache = self.cache();

        if let Ok(c) = cache.inner {
            // Upgrade type: `apt upgrade` (not `dist-upgrade`).
            // Allows adding packages, but not removing.
            // FIXME: release notes, we did a dist-upgrade before
            let upgrade_type = Upgrade::Upgrade;

            // Mark all packages for upgrade
            let res_mark = Self::apt_errors_to_output(c.upgrade(upgrade_type));
            if res_mark.inner.is_err() {
                return res_mark;
            }

            // Resolve dependencies
            let res_resolve = Self::apt_errors_to_output(c.resolve(true));
            if res_resolve.inner.is_err() {
                return res_resolve;
            }
            let res = res_mark.step(res_resolve);

            // Do the changes
            let mut install_progress = InstallProgress::apt();
            let mut acquire_progress = AcquireProgress::apt();
            let catch = OutputCatcher::new();
            let mut res_commit =
                Self::apt_errors_to_output(c.commit(&mut acquire_progress, &mut install_progress));
            let (out, err) = catch.read();
            res_commit.stdout(out);
            res_commit.stderr(err);
            res.step(res_commit)
        } else {
            cache.clear_ok()
        }
    }

    fn security_upgrade(&mut self) -> ResultOutput<()> {
        // This is tricky, there is nothing built-in in apt CLI. The only official way to do this is to use `unattended-upgrades`.
        // We try to copy the logic from `unattended-upgrades` here.
        //
        // https://help.ubuntu.com/community/AutomaticSecurityUpdates
        // https://www.debian.org/doc/manuals/securing-debian-manual/security-update.en.html
        // https://wiki.debian.org/UnattendedUpgrades

        let cache = self.cache();
        if let Ok(c) = cache.inner {
            let filter = PackageSort::default().installed().include_virtual();

            for p in c.packages(&filter) {
                p.versions().for_each(|v| {
                    // FIXME: get actual default rules from unattended-upgrades
                    if v.source_name().contains("security") {
                        v.set_candidate();
                        p.mark_install(false, false);
                    }
                });
            }

            // Resolve dependencies
            let res_resolve = Self::apt_errors_to_output(c.resolve(true));
            if res_resolve.inner.is_err() {
                return res_resolve;
            }

            // Do the changes
            let mut acquire_progress = AcquireProgress::apt();
            let mut install_progress = InstallProgress::apt();
            let catch = OutputCatcher::new();
            let mut res_commit =
                Self::apt_errors_to_output(c.commit(&mut acquire_progress, &mut install_progress));
            let (out, err) = catch.read();
            res_commit.stdout(out);
            res_commit.stderr(err);
            res_resolve.step(res_commit)
        } else {
            cache.clear_ok()
        }
    }

    fn upgrade(&mut self, packages: Vec<PackageSpec>) -> ResultOutput<()> {
        let cache = self.cache();

        if let Ok(c) = cache.inner {
            for p in packages {
                let package_id = if let Some(a) = p.architecture {
                    format!("{}:{}", p.name, a)
                } else {
                    p.name
                };
                // Get package from cache
                // FIXME: handle absent package
                let pkg = c.get(&package_id).unwrap();

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
            let res_resolve = Self::apt_errors_to_output(c.resolve(true));
            if res_resolve.inner.is_err() {
                return res_resolve;
            }

            // Do the changes
            let mut acquire_progress = AcquireProgress::apt();
            let mut install_progress = InstallProgress::apt();
            let catch = OutputCatcher::new();
            let mut res_commit =
                Self::apt_errors_to_output(c.commit(&mut acquire_progress, &mut install_progress));
            let (out, err) = catch.read();
            res_commit.stdout(out);
            res_commit.stderr(err);
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
}
