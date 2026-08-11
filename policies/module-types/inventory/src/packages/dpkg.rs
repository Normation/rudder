// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

//! Installed software from the dpkg database.

use std::{process::Command, str};

use anyhow::{Context, Result};
use rudder_module_type::os_release::OsRelease;

use crate::{
    empty_to_none, find_in_path,
    packages::{Package, PackageManager},
};

const DPKG_QUERY: &str = "dpkg-query";

/// The fields we query, tab-separated, in the order [`Dpkg::parse_installed`] expects them.
///
/// We only ask for what we report, plus the status we filter on. `Source:Package` and
/// `Source:Version` fall back to the binary package name and version when the package is its
/// own source, so they are always set in practice.
const SHOW_FORMAT: &str = concat!(
    "${Package}\t",
    "${Architecture}\t",
    "${Version}\t",
    "${Status}\t",
    "${Source:Package}\t",
    "${Source:Version}\n"
);

pub struct Dpkg;

impl PackageManager for Dpkg {
    fn is_available() -> bool {
        find_in_path(DPKG_QUERY).is_some()
    }

    fn installed(os_release: &OsRelease) -> Result<Vec<Package>> {
        let out = Command::new(DPKG_QUERY)
            .args(["--show", "--showformat", SHOW_FORMAT])
            .output()
            .with_context(|| format!("running {DPKG_QUERY}"))?;
        // dpkg-query exits with 1 when no package matches, which is not an error for us.
        let stdout = str::from_utf8(&out.stdout)
            .with_context(|| format!("non-UTF-8 output from {DPKG_QUERY}"))?;
        Ok(Self::parse_installed(
            stdout,
            Self::publisher(&os_release.id),
        ))
    }
}

impl Dpkg {
    /// dpkg has no per-package vendor field, and Rudder aggregates software by name and
    /// publisher, so we need a stable distribution-wide value. FusionInventory uses
    /// `lsb_release -i` ("Debian", "Ubuntu"); capitalizing the `ID` of `/etc/os-release`
    /// gives the same result without depending on the LSB tooling.
    fn publisher(os_release_id: &str) -> Option<String> {
        let mut chars = os_release_id.chars();
        let first = chars.next()?;
        Some(first.to_uppercase().collect::<String>() + chars.as_str())
    }

    fn parse_installed(list: &str, publisher: Option<String>) -> Vec<Package> {
        let mut res = vec![];
        for line in list.lines() {
            let fields: Vec<&str> = line.split('\t').collect();
            let [name, arch, version, status, source_name, source_version] = fields[..] else {
                continue;
            };
            // Packages known to dpkg are not necessarily installed: removed ones keeping their
            // configuration have a "deinstall ok config-files" status, for example.
            if !status.ends_with(" installed") {
                continue;
            }
            res.push(Package {
                arch: empty_to_none(arch),
                comments: None,
                name: name.to_string(),
                publisher: publisher.clone(),
                source_name: empty_to_none(source_name),
                source_version: empty_to_none(source_version),
                version: version.to_string(),
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
    fn it_parses_installed() {
        // Real `dpkg-query` output, with the trailing newline of the last entry.
        let list = "acl\tamd64\t2.3.1-3\tinstall ok installed\tacl\t2.3.1-3\n\
             acpi-support-base\tall\t0.143-5.1\tinstall ok installed\tacpi-support\t0.143-5.1\n\
             dbus\tamd64\t1.14.10-1~deb12u1\tdeinstall ok config-files\tdbus\t1.14.10-1~deb12u1\n\
             gcc-12-base\tamd64\t12.2.0-14\tinstall ok installed\tgcc-12\t\n";
        assert_eq!(
            Dpkg::parse_installed(list, Some("Debian".to_string())),
            vec![
                Package {
                    arch: Some("amd64".to_string()),
                    comments: None,
                    name: "acl".to_string(),
                    publisher: Some("Debian".to_string()),
                    source_name: Some("acl".to_string()),
                    source_version: Some("2.3.1-3".to_string()),
                    version: "2.3.1-3".to_string(),
                },
                Package {
                    arch: Some("all".to_string()),
                    comments: None,
                    name: "acpi-support-base".to_string(),
                    publisher: Some("Debian".to_string()),
                    source_name: Some("acpi-support".to_string()),
                    source_version: Some("0.143-5.1".to_string()),
                    version: "0.143-5.1".to_string(),
                },
                // "dbus" is skipped: only its configuration files are left on the system.
                Package {
                    arch: Some("amd64".to_string()),
                    comments: None,
                    name: "gcc-12-base".to_string(),
                    publisher: Some("Debian".to_string()),
                    source_name: Some("gcc-12".to_string()),
                    // An empty field is reported as no value at all.
                    source_version: None,
                    version: "12.2.0-14".to_string(),
                },
            ]
        );
    }

    /// Reads the packages of the machine we run on, when it is a dpkg one. `dpkg-query` needs no
    /// privilege, so this is the whole read and parse path.
    #[test]
    fn it_reads_the_installed_packages_of_this_machine() {
        let _guard = crate::no_concurrent_fork();
        if !Dpkg::is_available() {
            return;
        }
        let os_release = OsRelease::new().expect("no os-release");
        let installed = Dpkg::installed(&os_release).expect("could not read the packages");
        assert!(!installed.is_empty(), "a dpkg machine with no package");
        for package in &installed {
            assert!(!package.name.is_empty());
            assert!(!package.version.is_empty());
            // Every package of a dpkg machine has an architecture and a source.
            assert!(
                package.arch.is_some(),
                "{} has no architecture",
                package.name
            );
            assert!(
                package.source_name.is_some(),
                "{} has no source package",
                package.name
            );
        }
    }

    #[test]
    fn it_derives_the_publisher_from_os_release() {
        assert_eq!(Dpkg::publisher("debian"), Some("Debian".to_string()));
        assert_eq!(Dpkg::publisher("ubuntu"), Some("Ubuntu".to_string()));
        assert_eq!(Dpkg::publisher(""), None);
    }

    #[test]
    fn it_ignores_truncated_lines() {
        assert_eq!(Dpkg::parse_installed("acl\tamd64\t2.3.1-3\n", None), vec![]);
        assert_eq!(Dpkg::parse_installed("", None), vec![]);
    }
}
