// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

//! Pending updates, as computed by APT.
//!
//! We ask APT for the packages a `dist-upgrade` would change instead of reading
//! `apt list --upgradable`, so that updates only reachable through a dependency change are
//! reported too. This never touches the system: the package lists are not refreshed either,
//! so the result is only as fresh as the last `apt-get update`.

use std::{process::Command, str};

use anyhow::{Context, Result};
use regex::regex;

use crate::{
    empty_to_none, find_in_path,
    packages::{Update, UpdateManager},
};

const APT_GET: &str = "apt-get";

pub struct AptGet;

impl UpdateManager for AptGet {
    fn is_available() -> bool {
        find_in_path(APT_GET).is_some()
    }

    fn updates() -> Result<Vec<Update>> {
        let out = Command::new(APT_GET)
            .args(["--simulate", "dist-upgrade"])
            .output()
            .with_context(|| format!("running {APT_GET}"))?;
        // apt-get gives up with a non-zero status when it cannot compute a solution, after
        // having listed the changes it did resolve. Report those rather than nothing.
        let stdout = str::from_utf8(&out.stdout)
            .with_context(|| format!("non-UTF-8 output from {APT_GET}"))?;
        Ok(Self::parse_updates(stdout))
    }
}

impl AptGet {
    fn parse_updates(out: &str) -> Vec<Update> {
        // Inst openssh-server [1:8.2p1-4ubuntu0.8] (1:8.2p1-4ubuntu0.9 Ubuntu:20.04/focal-updates, Ubuntu:20.04/focal-security [amd64])
        // Inst libnftables1 [1.0.6-2] (1.0.6-2+deb12u1 Debian:12.1/stable [amd64])
        //
        // The current version is absent for packages pulled in as new dependencies. The
        // architecture qualifier of the name and the trailing "[]" marker are ignored.
        let re = regex!(
            r"^Inst\s+(?<name>[^\s:]+)(?::\S+)?\s+(?:\[[^\]\s]+\]\s+)?\((?<version>\S+)\s+(?<source>[^\[]+?)(?:\s+\[(?<arch>[^\]\s]+)\])?\)"
        );
        let mut res = vec![];
        for line in out.lines() {
            let Some(caps) = re.captures(line) else {
                continue;
            };
            let source = caps["source"].trim();
            res.push(Update {
                arch: caps.name("arch").map(|a| a.as_str().to_string()),
                from: APT_GET.to_string(),
                // Debian-based distributions do not tell us why a package is upgradable, so
                // the best we can do is look at the origin it comes from.
                kind: Some(
                    if source.to_lowercase().contains("security") {
                        "security"
                    } else {
                        "none"
                    }
                    .to_string(),
                ),
                name: caps["name"].to_string(),
                source: empty_to_none(source),
                version: caps["version"].to_string(),
                description: None,
                severity: None,
                ids: None,
            })
        }
        res
    }
}

#[cfg(test)]
mod tests {
    use pretty_assertions::assert_eq;

    use super::*;

    #[test]
    fn it_parses_updates() {
        let out = r#"NOTE: This is only a simulation!
      apt-get needs root privileges for real execution.
Reading package lists...
Building dependency tree...
Reading state information...
Calculating upgrade...
The following NEW packages will be installed:
  linux-image-6.1.0-28-amd64
The following packages will be upgraded:
  base-files libc6 openssh-server
Inst base-files [12.4+deb12u5] (12.4+deb12u15 Debian:12.15/oldstable [amd64])
Conf base-files (12.4+deb12u15 Debian:12.15/oldstable [amd64])
Inst libc6 [2.36-9+deb12u7] (2.36-9+deb12u10 Debian-Security:12/stable-security [amd64]) []
Inst openssh-server:amd64 [1:9.2p1-2+deb12u3] (1:9.2p1-2+deb12u5 Debian:12.15/oldstable, Debian-Security:12/stable-security [amd64])
Inst linux-image-6.1.0-28-amd64 (6.1.119-1 Debian:12.15/oldstable [amd64])
Remv linux-image-6.1.0-26-amd64 [6.1.112-1]
"#;
        assert_eq!(
            AptGet::parse_updates(out),
            vec![
                Update {
                    arch: Some("amd64".to_string()),
                    from: "apt-get".to_string(),
                    kind: Some("none".to_string()),
                    name: "base-files".to_string(),
                    source: Some("Debian:12.15/oldstable".to_string()),
                    version: "12.4+deb12u15".to_string(),
                    description: None,
                    severity: None,
                    ids: None,
                },
                Update {
                    arch: Some("amd64".to_string()),
                    from: "apt-get".to_string(),
                    kind: Some("security".to_string()),
                    name: "libc6".to_string(),
                    source: Some("Debian-Security:12/stable-security".to_string()),
                    version: "2.36-9+deb12u10".to_string(),
                    description: None,
                    severity: None,
                    ids: None,
                },
                // Several origins provide the update: the security one wins.
                Update {
                    arch: Some("amd64".to_string()),
                    from: "apt-get".to_string(),
                    kind: Some("security".to_string()),
                    name: "openssh-server".to_string(),
                    source: Some(
                        "Debian:12.15/oldstable, Debian-Security:12/stable-security".to_string()
                    ),
                    version: "1:9.2p1-2+deb12u5".to_string(),
                    description: None,
                    severity: None,
                    ids: None,
                },
                // A new package, without a currently installed version.
                Update {
                    arch: Some("amd64".to_string()),
                    from: "apt-get".to_string(),
                    kind: Some("none".to_string()),
                    name: "linux-image-6.1.0-28-amd64".to_string(),
                    source: Some("Debian:12.15/oldstable".to_string()),
                    version: "6.1.119-1".to_string(),
                    description: None,
                    severity: None,
                    ids: None,
                },
            ]
        );
    }

    /// Reads the pending updates of the machine we run on, when it is an apt one. `apt-get
    /// --simulate` needs no privilege, so this is the whole read and parse path. A machine with
    /// nothing to upgrade is a valid answer, so only the shape of what we find is asserted.
    #[test]
    fn it_reads_the_pending_updates_of_this_machine() {
        let _guard = crate::no_concurrent_fork();
        if !AptGet::is_available() {
            return;
        }
        for update in AptGet::updates().expect("could not read the updates") {
            assert!(!update.name.is_empty());
            assert!(!update.version.is_empty());
            assert_eq!(update.from, "apt-get");
            // The heuristic always decides one way or the other.
            assert!(matches!(update.kind.as_deref(), Some("none" | "security")));
            assert!(update.source.is_some(), "{} has no origin", update.name);
        }
    }

    #[test]
    fn it_parses_updates_without_architecture() {
        assert_eq!(
            AptGet::parse_updates("Inst dpkg [1.21.22] (1.21.23 Debian:12.15/oldstable)\n"),
            vec![Update {
                arch: None,
                from: "apt-get".to_string(),
                kind: Some("none".to_string()),
                name: "dpkg".to_string(),
                source: Some("Debian:12.15/oldstable".to_string()),
                version: "1.21.23".to_string(),
                description: None,
                severity: None,
                ids: None,
            }]
        );
    }

    #[test]
    fn it_ignores_output_without_updates() {
        assert_eq!(
            AptGet::parse_updates("Reading package lists...\nCalculating upgrade...\nDone\n"),
            vec![]
        );
    }
}
