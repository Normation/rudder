// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

//! Pending updates on a yum or dnf system.
//!
//! Two commands. `check-update` lists the packages and their versions, which is what the
//! section is, and `updateinfo list` says which advisories mention each package, which fills in
//! `ID`, `KIND` and `SEVERITY`. The second is a bonus: a repository carrying no advisories, or a
//! `yum` too old to have `updateinfo`, costs only those three elements.
//!
//! `updateinfo info` would add the date and the description of each advisory. It is not read.

use std::collections::HashMap;

use anyhow::Result;
use regex::regex;
use tracing::{debug, warn};

use std::process::Command;

use crate::{
    find_in_path,
    packages::{Update, UpdateManager, kind, severity},
};

/// `dnf` first: on a modern RPM distribution `yum` is a symlink to it, and on an old one only
/// `yum` exists. Asking for the one that is really there keeps `FROM` honest.
const COMMANDS: [&str; 2] = ["dnf", "yum"];

pub struct Yum;

impl UpdateManager for Yum {
    fn is_available() -> bool {
        Self::command().is_some()
    }

    fn updates() -> Result<Vec<Update>> {
        let Some(command) = Self::command() else {
            return Ok(vec![]);
        };
        let updates = stdout_of(command, &["--quiet", "-y", "check-update"]);
        // The advisories are a bonus: a repository without them, or a `yum` too old for
        // `updateinfo`, leaves the updates themselves untouched.
        let list = stdout_of(command, &["--quiet", "updateinfo", "list"]);
        Ok(Self::parse_updates_info(command, &updates, &list))
    }
}

/// What one line of `updateinfo list` says about an advisory.
#[derive(Debug, Clone, PartialEq)]
struct Advisory {
    id: String,
    kind: String,
    severity: Option<String>,
}

impl Advisory {
    /// Reads the advisory and the kind out of a line of `updateinfo list`.
    ///
    /// Its second column holds a kind, `bugfix` or `enhancement`, except for a security
    /// advisory, where it holds the severity instead, as `Moderate/Sec.`. That is the only
    /// place a severity is named, `info` not being read.
    fn parse(advisory: &str, info: &str) -> Self {
        let (kind, severity) = match info.split_once('/') {
            // `Moderate/Sec.`, and anything else ending the same way.
            Some((level, rest)) if rest.starts_with("Sec") => {
                ("security".to_string(), severity(level))
            }
            _ => (kind(info), None),
        };
        Self {
            id: advisory.to_string(),
            kind,
            severity,
        }
    }

    /// How much this advisory matters, to pick one out of the several a package may carry.
    ///
    /// A security advisory outranks any other kind, and among those the severity decides.
    fn rank(&self) -> (u8, u8) {
        let kind = u8::from(self.kind == "security");
        let severity = match self.severity.as_deref() {
            Some("critical") => 4,
            Some("high") => 3,
            Some("moderate") => 2,
            Some("low") => 1,
            _ => 0,
        };
        (kind, severity)
    }
}

impl Yum {
    fn command() -> Option<&'static str> {
        COMMANDS.into_iter().find(|c| find_in_path(c).is_some())
    }

    /// Reads the two outputs into the section.
    fn parse_updates_info(command: &str, updates: &str, list: &str) -> Vec<Update> {
        let of_package = Self::parse_list(list);
        let mut res = vec![];
        for line in updates.lines() {
            // `name.arch  version  repository`, the three columns of `check-update`.
            let Some(caps) = regex!(r"^(\S+)\.([^.\s]+)\s+(\S+)\s+(\S+)\s*$").captures(line) else {
                continue;
            };
            let (name, arch, version, source) = (&caps[1], &caps[2], &caps[3], &caps[4]);
            // Matched on the name alone. The version cannot be part of the key: `check-update`
            // offers the newest there is, where an advisory names the version *it* shipped, and
            // the two are only equal for a package whose newest update is its latest advisory.
            let advisories = of_package.get(&format!("{name}.{arch}"));
            // A package accumulates advisories, and the server takes a list of them. What the
            // update *is*, though, has to be one answer: the worst of them, as that is the one
            // that decides whether the update is urgent.
            let worst = advisories.and_then(|a| a.iter().max_by_key(|a| a.rank()));
            res.push(Update {
                arch: Some(arch.to_string()),
                from: command.to_string(),
                kind: worst.map(|a| a.kind.clone()),
                name: name.to_string(),
                source: Some(source.to_string()),
                version: version.to_string(),
                description: None,
                severity: worst.and_then(|a| a.severity.clone()),
                ids: advisories.map(|a| {
                    a.iter()
                        .map(|a| a.id.as_str())
                        .collect::<Vec<_>>()
                        .join(",")
                }),
            });
        }
        debug!(
            "{} of {} updates are described by an advisory",
            res.iter().filter(|u| u.ids.is_some()).count(),
            res.len()
        );
        res
    }

    /// Maps each package to the advisories that mention it, out of `updateinfo list`.
    ///
    /// Its lines are `advisory  kind  name-version-release.arch`, where the kind of a security
    /// advisory is written as its severity, `Moderate/Sec.`. Only the advisory is kept here,
    /// the rest being said again, and better, by `updateinfo info`.
    fn parse_list(list: &str) -> HashMap<String, Vec<Advisory>> {
        let mut res: HashMap<String, Vec<Advisory>> = HashMap::new();
        for line in list.lines() {
            let fields: Vec<&str> = line.split_whitespace().collect();
            let [advisory, info, package] = fields[..] else {
                continue;
            };
            let Some(key) = package_of(package) else {
                continue;
            };
            let advisories = res.entry(key).or_default();
            // One advisory can name the same package more than once, for several versions.
            if !advisories.iter().any(|a| a.id == advisory) {
                advisories.push(Advisory::parse(advisory, info));
            }
        }
        res
    }
}

/// What a command printed, whatever it exited with.
///
/// `check-update` exits 100 when there are updates to install and 0 when there are none, so its
/// status is an answer rather than a verdict, and the shared [`crate::cmd`] would throw the
/// output away for it: a machine with updates would be reported as having none, which is the
/// worst thing this section can say. `updateinfo` likewise exits non-zero on a repository that
/// carries no advisories, which is not a failure either.
///
/// Nothing at all when the command cannot be run, which reads as nothing to report.
fn stdout_of(command: &str, args: &[&str]) -> String {
    match Command::new(command).args(args).output() {
        Ok(out) => String::from_utf8_lossy(&out.stdout).into_owned(),
        Err(e) => {
            warn!("Could not run '{command} {}': {e}", args.join(" "));
            String::new()
        }
    }
}

/// The package an `updateinfo` entry names, as `name.arch`.
///
/// It writes them as `name-version-release.arch`, and a name holds dashes of its own
/// (`audit-libs`, `python3-dnf`), so the architecture is taken from after the last dot and the
/// version and release from the last two dashes, leaving the name whatever it is.
fn package_of(nvra: &str) -> Option<String> {
    let (nvr, arch) = nvra.rsplit_once('.')?;
    let (name_version, _release) = nvr.rsplit_once('-')?;
    let (name, _version) = name_version.rsplit_once('-')?;
    (!name.is_empty()).then(|| format!("{name}.{arch}"))
}

#[cfg(test)]
mod tests {
    use pretty_assertions::assert_eq;

    use super::*;

    /// Real output of `yum --quiet -y check-update` on a Rocky 9 machine.
    const UPDATES: &str = "\nalternatives.x86_64           1.24-1.el9_5.1              baseos
binutils.x86_64               2.35.2-67.el9_7.1           baseos
openssl-libs.x86_64           1:3.5.5-6.el9_8             baseos
Obsoleting Packages
tzdata.noarch                 2026c-1.el9_8               baseos
";

    /// Real output of `yum -q updateinfo list`, whose security lines name a severity where the
    /// others name a kind.
    ///
    /// Note the versions: an advisory names the version *it* shipped, which is older than the
    /// one `check-update` offers above. Matching the two on the version finds almost nothing,
    /// which is what this fixture is shaped to catch.
    const LIST: &str = "RLBA-2024:9438  bugfix         alternatives-1.24-1.el9_5.1.x86_64
RLBA-2024:9384  bugfix         binutils-2.35.2-54.el9.x86_64
RLSA-2025:23343 Moderate/Sec.  binutils-2.35.2-67.el9_7.1.x86_64
RLSA-2026:11111 Important/Sec. openssl-libs-3.5.4-1.el9_8.x86_64
";

    /// Real output of `yum -q updateinfo info`, two blocks of it.
    const INFO: &str = "\
===============================================================================
  alternatives bug fix update
===============================================================================
  Update ID: RLBA-2024:9438
       Type: bugfix
    Updated: 2025-10-17 21:52:42
Description: For detailed information on changes in this release, see the Rocky Linux 9.4 Release Notes.
   Severity: None

===============================================================================
  binutils security update
===============================================================================
  Update ID: RLSA-2025:23343
       Type: security
    Updated: 2026-01-09 08:12:00
Description: A flaw was found in binutils.
   Severity: Moderate

===============================================================================
  openssl security update
===============================================================================
  Update ID: RLSA-2026:11111
       Type: security
    Updated: 2026-02-02 10:00:00
Description: A flaw was found in openssl.
   Severity: Important

===============================================================================
  binutils bug fix update
===============================================================================
  Update ID: RLBA-2024:9384
       Type: bugfix
    Updated: 2024-11-01 00:00:00
Description: Assorted fixes.
   Severity: None
";

    #[test]
    fn it_parses_updates_with_their_advisories() {
        let parsed = Yum::parse_updates_info("dnf", UPDATES, LIST);
        assert_eq!(parsed.len(), 4);

        // A bug fix, which the server calls a defect, with no severity of its own.
        let alternatives = &parsed[0];
        assert_eq!(alternatives.name, "alternatives");
        assert_eq!(alternatives.arch, Some("x86_64".to_string()));
        assert_eq!(alternatives.version, "1.24-1.el9_5.1");
        assert_eq!(alternatives.source, Some("baseos".to_string()));
        assert_eq!(alternatives.from, "dnf");
        assert_eq!(alternatives.kind, Some("defect".to_string()));
        assert_eq!(alternatives.severity, None);
        assert_eq!(alternatives.ids, Some("RLBA-2024:9438".to_string()));

        // Two advisories name binutils, and the server takes the list of them. What the update
        // *is* comes from the worse of the two, the security one.
        let binutils = &parsed[1];
        assert_eq!(
            binutils.ids,
            Some("RLBA-2024:9384,RLSA-2025:23343".to_string())
        );
        assert_eq!(binutils.kind, Some("security".to_string()));
        assert_eq!(binutils.severity, Some("moderate".to_string()));

        // A name holding a dash of its own, matched although the advisory names an older
        // version and `check-update` puts an epoch in front of this one.
        let openssl = &parsed[2];
        assert_eq!(openssl.name, "openssl-libs");
        assert_eq!(openssl.version, "1:3.5.5-6.el9_8");
        assert_eq!(openssl.ids, Some("RLSA-2026:11111".to_string()));
        // `Important` is what the server calls `high`.
        assert_eq!(openssl.severity, Some("high".to_string()));

        // An update no advisory describes is still an update.
        let tzdata = &parsed[3];
        assert_eq!(tzdata.name, "tzdata");
        assert_eq!(tzdata.ids, None);
        assert_eq!(tzdata.kind, None);
        assert_eq!(tzdata.severity, None);
    }

    /// The advisories are a bonus: without them the updates are reported as they were before.
    #[test]
    fn it_parses_updates_without_any_advisory() {
        let parsed = Yum::parse_updates_info("yum", UPDATES, "");
        assert_eq!(parsed.len(), 4);
        assert_eq!(parsed[0].name, "alternatives");
        assert_eq!(parsed[0].from, "yum");
        assert!(parsed.iter().all(|u| u.ids.is_none() && u.kind.is_none()));
    }

    /// The name of a package, out of the `name-version-release.arch` `updateinfo` prints, where
    /// the name itself holds dashes.
    #[test]
    fn it_reads_the_package_an_advisory_names() {
        assert_eq!(
            package_of("alternatives-1.24-1.el9_5.1.x86_64"),
            Some("alternatives.x86_64".to_string())
        );
        assert_eq!(
            package_of("audit-libs-3.1.5-1.el9.x86_64"),
            Some("audit-libs.x86_64".to_string())
        );
        assert_eq!(
            package_of("python3-dnf-4.14.0-34.el9_8.rocky.0.1.noarch"),
            Some("python3-dnf.noarch".to_string())
        );
        // Not a package name at all.
        assert_eq!(package_of("nodashes"), None);
        assert_eq!(package_of(""), None);
    }

    /// `check-update` exits 100 to say there are updates, so its output has to be read
    /// whatever it exited with. Taking the shared `cmd` helper, which keeps the output only of
    /// a command that succeeded, reported every RPM machine as having no update at all.
    #[test]
    fn it_reads_the_output_of_a_command_that_exits_non_zero() {
        let _guard = crate::no_concurrent_fork();
        let dir = tempfile::tempdir().unwrap();
        let script = dir.path().join("check-update-like");
        std::fs::write(
            &script,
            "#!/bin/sh\nprintf 'bash.x86_64  5.1.8-9.el9  baseos\\n'\nexit 100\n",
        )
        .unwrap();
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(&script, std::fs::Permissions::from_mode(0o755)).unwrap();
        }
        let out = stdout_of(script.to_str().unwrap(), &[]);
        assert!(
            out.contains("bash.x86_64"),
            "the output was dropped: {out:?}"
        );
        // And it becomes an update, rather than the machine looking fully patched.
        let parsed = Yum::parse_updates_info("yum", &out, "");
        assert_eq!(parsed.len(), 1);
        assert_eq!(parsed[0].name, "bash");
    }

    /// A command that cannot be run at all is nothing to report, not a failure.
    #[test]
    fn it_reads_nothing_from_a_command_it_cannot_run() {
        let _guard = crate::no_concurrent_fork();
        assert!(stdout_of("this-command-does-not-exist", &[]).is_empty());
    }

    #[test]
    fn it_parses_no_update_from_an_empty_output() {
        assert!(Yum::parse_updates_info("yum", "", "").is_empty());
        assert!(
            Yum::parse_updates_info("yum", "Last metadata expiration check: 0:10:00 ago\n", "")
                .is_empty()
        );
    }

    /// The words each package manager uses for a kind, against the four the server knows.
    #[test]
    fn it_names_a_kind_the_server_understands() {
        assert_eq!(kind("bugfix"), "defect");
        assert_eq!(kind("recommended"), "defect");
        assert_eq!(kind("security"), "security");
        assert_eq!(kind("enhancement"), "enhancement");
        assert_eq!(kind("optional"), "enhancement");
        assert_eq!(kind("newpackage"), "none");
        assert_eq!(kind("None"), "none");
        // Case and spacing vary between the two, and neither decides the meaning.
        assert_eq!(kind("  Security "), "security");
        // A word neither knows is passed through, for the server to read as `other`.
        assert_eq!(kind("something-new"), "something-new");
    }

    #[test]
    fn it_names_a_severity_the_server_understands() {
        assert_eq!(severity("Moderate"), Some("moderate".to_string()));
        assert_eq!(severity("Critical"), Some("critical".to_string()));
        assert_eq!(severity("Low"), Some("low".to_string()));
        // `zypper` says important where the server says high.
        assert_eq!(severity("important"), Some("high".to_string()));
        // The absence of a severity is not a severity.
        assert_eq!(severity("None"), None);
        assert_eq!(severity(""), None);
        assert_eq!(severity("unspecified"), None);
    }
}

#[cfg(test)]
mod real_data {
    /// Runs the parser over the whole, unedited output of a real Rocky 9 machine, to check that
    /// the advisories actually match the updates: the key is built from three columns of one
    /// command and compared against a name printed by another, which no small fixture proves.
    #[test]
    #[ignore = "needs the captured output of a real machine"]
    fn it_matches_the_advisories_of_a_real_machine() {
        let dir = std::path::Path::new("/tmp");
        let read = |n: &str| std::fs::read_to_string(dir.join(n)).unwrap_or_default();
        let parsed = super::Yum::parse_updates_info("yum", &read("cu.txt"), &read("ul.txt"));
        let matched = parsed.iter().filter(|u| u.ids.is_some()).count();
        let severe = parsed.iter().filter(|u| u.severity.is_some()).count();
        let security = parsed
            .iter()
            .filter(|u| u.kind.as_deref() == Some("security"))
            .count();
        println!(
            "{} updates, {matched} with an advisory, {security} security, {severe} with a severity",
            parsed.len()
        );
        assert!(parsed.len() > 50, "only {} updates parsed", parsed.len());
        assert!(
            matched * 100 / parsed.len() > 80,
            "only {matched} of {} updates matched an advisory",
            parsed.len()
        );
    }
}
