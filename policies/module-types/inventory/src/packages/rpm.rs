// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

//! Installed software on an RPM system.

use anyhow::Result;
use rudder_module_type::os_release::OsRelease;
use tracing::debug;

use crate::{
    packages::{Package, PackageManager},
    util::{cmd, find_in_path},
};

const RPM: &str = "rpm";

/// What we ask of every package, in one line each.
///
/// The summary is last because it is the only free text of the six, so a tab it may hold cannot
/// push the fields we parse by position out of place.
const QUERY_FORMAT: &str =
    "%{NAME}\t%{VERSION}-%{RELEASE}\t%{VENDOR}\t%{EPOCH}\t%{ARCH}\t%{SUMMARY}\n";

/// How many fields [`QUERY_FORMAT`] produces.
const FIELDS: usize = 6;

pub struct Rpm;

impl PackageManager for Rpm {
    fn is_available() -> bool {
        find_in_path(RPM).is_some()
    }

    fn installed(_os_release: &OsRelease) -> Result<Vec<Package>> {
        // Looked up in `PATH`, as `is_available` does: `rpm` is not in the same place on every
        // distribution, and a path of our own would disagree with the check that got us here.
        let out = cmd(RPM, &["-qa", "--queryformat", QUERY_FORMAT])?;
        Ok(Self::parse_installed(&out))
    }
}

impl Rpm {
    /// Reads what `rpm` printed, one package per line.
    ///
    /// A line we cannot read is skipped rather than reported: `rpm` writes what the package
    /// holds, and one malformed entry is not a reason to lose the whole software list, which
    /// indexing the fields blindly used to do.
    fn parse_installed(list: &str) -> Vec<Package> {
        let mut res = vec![];
        let mut skipped = 0;
        for line in list.lines().filter(|l| !l.trim().is_empty()) {
            let fields: Vec<&str> = line.splitn(FIELDS, '\t').collect();
            let [name, version, vendor, epoch, arch, summary] = fields[..] else {
                skipped += 1;
                continue;
            };
            res.push(Package {
                arch: unset(arch),
                comments: unset(summary),
                name: name.to_string(),
                publisher: unset(vendor),
                // RPM has no equivalent of the source package a deb names.
                source_name: None,
                source_version: None,
                version: match unset(epoch) {
                    // An epoch is part of the version, and tells two otherwise equal ones apart.
                    Some(epoch) if epoch != "0" => format!("{epoch}:{version}"),
                    _ => version.to_string(),
                },
            });
        }
        if skipped > 0 {
            debug!("Skipped {skipped} package(s) rpm described in a way we cannot read");
        }
        res
    }
}

/// A value `rpm` has nothing to put in, which it writes as `(none)` rather than leaving empty.
fn unset(value: &str) -> Option<String> {
    match value.trim() {
        "" | "(none)" => None,
        value => Some(value.to_string()),
    }
}

#[cfg(test)]
mod tests {
    use pretty_assertions::assert_eq;

    use super::*;

    fn package(
        name: &str,
        version: &str,
        publisher: Option<&str>,
        arch: Option<&str>,
        comments: &str,
    ) -> Package {
        Package {
            arch: arch.map(str::to_string),
            comments: Some(comments.to_string()),
            name: name.to_string(),
            publisher: publisher.map(str::to_string),
            source_name: None,
            source_version: None,
            version: version.to_string(),
        }
    }

    /// Real output of the query we run, from a Rocky 9 machine.
    #[test]
    fn it_parses_installed() {
        let list = "libgcc\t11.4.1-2.1.el9\tRocky Enterprise Software Foundation\t(none)\tx86_64\tGCC version 11 shared support library
crypto-policies\t20230731-1.git94f0e2c.el9_3.1\tRocky Enterprise Software Foundation\t(none)\tnoarch\tSystem-wide crypto policies
dbus-daemon\t1.12.8-24.el8\tAlmaLinux\t1\tx86_64\tD-BUS message bus
gpg-pubkey\t6f07d355-509cdb91\t(none)\t(none)\t(none)\tgpg(Rudder Project (RPM release key) <security@rudder-project.org>)
";
        assert_eq!(
            Rpm::parse_installed(list),
            vec![
                package(
                    "libgcc",
                    "11.4.1-2.1.el9",
                    Some("Rocky Enterprise Software Foundation"),
                    Some("x86_64"),
                    "GCC version 11 shared support library"
                ),
                package(
                    "crypto-policies",
                    "20230731-1.git94f0e2c.el9_3.1",
                    Some("Rocky Enterprise Software Foundation"),
                    Some("noarch"),
                    "System-wide crypto policies"
                ),
                // The epoch is put back in front of the version, as it is part of it.
                package(
                    "dbus-daemon",
                    "1:1.12.8-24.el8",
                    Some("AlmaLinux"),
                    Some("x86_64"),
                    "D-BUS message bus"
                ),
                // A key has neither vendor nor architecture, and is still a package.
                package(
                    "gpg-pubkey",
                    "6f07d355-509cdb91",
                    None,
                    None,
                    "gpg(Rudder Project (RPM release key) <security@rudder-project.org>)"
                ),
            ]
        );
    }

    /// An epoch of zero is the absence of one, and is not written into the version.
    #[test]
    fn it_leaves_a_zero_epoch_out_of_the_version() {
        let list = "bash\t5.1.8-9.el9\tRocky\t0\tx86_64\tThe GNU Bourne Again shell\n";
        assert_eq!(Rpm::parse_installed(list)[0].version, "5.1.8-9.el9");
    }

    /// A summary is free text and may hold a tab, which must not shift the fields.
    #[test]
    fn it_reads_a_summary_holding_a_tab() {
        let list = "weird\t1.0-1\tVendor\t(none)\tnoarch\tA summary\twith a tab\n";
        let parsed = Rpm::parse_installed(list);
        assert_eq!(parsed.len(), 1);
        assert_eq!(parsed[0].name, "weird");
        assert_eq!(
            parsed[0].comments,
            Some("A summary\twith a tab".to_string())
        );
    }

    /// One unreadable line used to panic on an index, losing the whole section with it.
    #[test]
    fn it_skips_a_line_it_cannot_read_and_keeps_the_others() {
        let list = "bash\t5.1.8-9.el9\tRocky\t0\tx86_64\tThe GNU Bourne Again shell
this line has no tabs at all
short\tfields\tonly
sed\t4.8-10.el9\tRocky\t0\tx86_64\tA GNU stream text editor
";
        let parsed = Rpm::parse_installed(list);
        assert_eq!(parsed.len(), 2);
        assert_eq!(parsed[0].name, "bash");
        assert_eq!(parsed[1].name, "sed");
    }

    #[test]
    fn it_parses_no_package_from_an_empty_output() {
        assert!(Rpm::parse_installed("").is_empty());
        assert!(Rpm::parse_installed("\n\n").is_empty());
    }
}
