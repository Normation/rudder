// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

//! The `OPERATINGSYSTEM` section: what the distribution says it is, what the kernel says it is,
//! and the timezone the machine is set to.

use anyhow::{Context, Result};
use jiff::Zoned;
use nix::sys::utsname::uname;
use rudder_module_type::os_release::{OS_RELEASE_PATHS, OsRelease};
use serde::Serialize;
use tracing::{debug, warn};

/// `OsRelease` names the system `Linux`, with no version at all, when it finds nothing to read,
/// and a machine we cannot identify is inventoried under that name rather than not at all.
pub fn os_release() -> Result<OsRelease> {
    if OsRelease::path().is_none() {
        warn!(
            "No {} to read the operating system from, reporting a generic Linux",
            OS_RELEASE_PATHS.join(" or ")
        );
    }
    OsRelease::new().context("Reading the operating system release")
}

#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
pub struct OperatingSystem {
    // <ARCH>x86_64</ARCH>
    arch: String,
    // <FQDN>server.rudder.local</FQDN>
    fqdn: String,
    // <FULL_NAME>CentOS Stream release 8</FULL_NAME>
    full_name: String,
    // <KERNEL_NAME>linux</KERNEL_NAME>
    kernel_name: String,
    // <KERNEL_VERSION>4.18.0-365.el8.x86_64</KERNEL_VERSION>
    kernel_version: String,
    // <NAME>CentOS</NAME>
    name: String,
    /// Only SUSE has one, as part of its version string.
    #[serde(rename = "SERVICE_PACK", skip_serializing_if = "Option::is_none")]
    service_pack: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    timezone: Option<Timezone>,
    // <VERSION>8</VERSION>
    version: String,
}

impl OperatingSystem {
    /// The fully qualified name is read once for the whole inventory, as the `RUDDER` section
    /// reports it too, so it is handed over rather than resolved again here.
    pub fn inventory(os_release: &OsRelease, fqdn: String) -> Result<Self> {
        let uts = uname().context("Reading the kernel identification")?;

        let (version, service_pack) = version_and_service_pack(
            os_release.version.as_deref(),
            os_release.version_id.as_deref(),
        );
        if let Some(ref service_pack) = service_pack {
            debug!("Operating system is version {version} service pack {service_pack}");
        }

        Ok(Self {
            arch: uts.machine().to_string_lossy().into_owned(),
            fqdn,
            full_name: os_release.pretty_name.clone(),
            kernel_name: uts.sysname().to_string_lossy().to_lowercase(),
            kernel_version: uts.release().to_string_lossy().into_owned(),
            name: os_release.name.clone(),
            service_pack,
            timezone: Timezone::new(),
            version,
        })
    }
}

/// The version of the operating system, and the service pack it may carry.
///
/// FusionInventory reports the version out of one of two modules, and which one runs decides
/// what a version is:
///
/// * `Distro::LSB` runs on any distribution that has `lsb_release`, which is almost all of
///   them, and reports the `Release:` it prints. That is the bare number, `26.04` and not
///   `26.04 LTS (Resolute Raccoon)`, and it is what `VERSION_ID` holds.
/// * `Distro::NonLSB` runs on SUSE, on Oracle, and where there is no `lsb_release`. Falling
///   back to `/etc/os-release`, it reports `VERSION`, which is where SUSE carries its service
///   pack as `15-SP5` and where the two are split apart.
///
/// We read `/etc/os-release` and nothing else, so we take `VERSION_ID` as the version, and
/// `VERSION` only when it names a service pack, which is the one case its extra content is
/// what we are after. Both agents then report Ubuntu as `26.04`, and `15` with a
/// service pack of `5` for a SLES 15 SP5 one.
fn version_and_service_pack(
    version: Option<&str>,
    version_id: Option<&str>,
) -> (String, Option<String>) {
    // A version ending in "-SP" names no service pack, and is not the SUSE case.
    if let Some((base, service_pack)) = version.and_then(|v| v.rsplit_once("-SP"))
        && !service_pack.is_empty()
    {
        return (base.to_string(), Some(service_pack.to_string()));
    }
    // `VERSION_ID` is optional, where a distribution that omits it leaves us with `VERSION`.
    (version_id.or(version).unwrap_or_default().to_string(), None)
}

/// The local timezone, which the server only keeps when it has both a name and an offset.
#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
pub struct Timezone {
    /// The IANA name of the zone, like `Europe/Paris`.
    name: String,
    /// The current offset from UTC, like `+0200`. It depends on the date, as most zones have
    /// a summer time.
    offset: String,
}

/// The IANA names that are another way of writing `UTC`.
///
/// The zone database keeps a name it has ever published working forever, as a link to the zone
/// that replaced it, and these seventeen all link to `UTC`.
///
/// To be more Fusion compatible.
const UTC_ALIASES: &[&str] = &[
    "Etc/GMT",
    "Etc/GMT+0",
    "Etc/GMT-0",
    "Etc/GMT0",
    "Etc/Greenwich",
    "Etc/UCT",
    "Etc/UTC",
    "Etc/Universal",
    "Etc/Zulu",
    "GMT",
    "GMT+0",
    "GMT-0",
    "GMT0",
    "Greenwich",
    "UCT",
    "Universal",
    "Zulu",
];

/// The name of a zone, under the one name FusionInventory would report it by, when we know it.
///
/// FusionInventory asks `DateTime::TimeZone` for the zone, which resolves the links of the
/// database before naming it, so a machine whose `/etc/localtime` points at `Etc/UTC` is
/// reported as `UTC`. `jiff` reports the name it was given, which is the more accurate answer:
/// `Etc/UTC` is what the machine is really configured with. The server keeps the name as it is
/// written, though, so two agents disagreeing means the same machine changes timezone in the
/// interface depending on which one inventoried it.
fn zone_name(name: &str) -> String {
    if UTC_ALIASES.contains(&name) {
        return "UTC".to_string();
    }
    name.to_string()
}

impl Timezone {
    /// Both values come from the same zone, so they cannot disagree.
    fn new() -> Option<Self> {
        let now = Zoned::now();
        Some(Self {
            name: zone_name(now.time_zone().iana_name()?),
            offset: now.strftime("%z").to_string(),
        })
    }
}

#[cfg(test)]
mod tests {
    use pretty_assertions::assert_eq;

    use super::*;

    #[test]
    fn it_splits_the_suse_service_pack_out_of_the_version() {
        // What SLES 15 and 12 report, where the service pack has to be reported on its own and
        // `VERSION_ID` would give "15.5" and no service pack at all.
        assert_eq!(
            version_and_service_pack(Some("15-SP5"), Some("15.5")),
            ("15".to_string(), Some("5".to_string()))
        );
        assert_eq!(
            version_and_service_pack(Some("12-SP3"), Some("12.3")),
            ("12".to_string(), Some("3".to_string()))
        );
        // SLES without a service pack, and openSUSE Leap, which never has one.
        assert_eq!(
            version_and_service_pack(Some("15"), Some("15")),
            ("15".to_string(), None)
        );
        assert_eq!(
            version_and_service_pack(Some("15.5"), Some("15.5")),
            ("15.5".to_string(), None)
        );
        // A version naming no service pack must not report an empty one.
        assert_eq!(
            version_and_service_pack(Some("15-SP"), Some("15")),
            ("15".to_string(), None)
        );
    }

    /// The bare number `lsb_release -r` prints, which is what FusionInventory reports on every
    /// distribution that has it, rather than the sentence `VERSION` holds.
    #[test]
    fn it_reports_the_version_without_what_the_distribution_adds_to_it() {
        assert_eq!(
            version_and_service_pack(Some("26.04 LTS (Resolute Raccoon)"), Some("26.04")),
            ("26.04".to_string(), None)
        );
        assert_eq!(
            version_and_service_pack(Some("12 (bookworm)"), Some("12")),
            ("12".to_string(), None)
        );
        // A distribution that names no `VERSION_ID` leaves us with `VERSION`.
        assert_eq!(
            version_and_service_pack(Some("rolling"), None),
            ("rolling".to_string(), None)
        );
        // And one that names neither, which the server refuses. Nothing to report is still
        // better than refusing to run over it.
        assert_eq!(version_and_service_pack(None, None), (String::new(), None));
    }

    /// Checks the whole chain on the two machines the two FusionInventory modules cover.
    #[test]
    fn it_reports_the_version_of_a_machine_as_fusion_inventory_does() {
        // A SLES 15 SP5 machine, which FusionInventory inventories with `Distro::NonLSB`.
        let sles = OsRelease::from_string(
            r#"NAME="SLES"
VERSION="15-SP5"
VERSION_ID="15.5"
PRETTY_NAME="SUSE Linux Enterprise Server 15 SP5"
ID="sles"
ID_LIKE="suse"
CPE_NAME="cpe:/o:suse:sles:15:sp5"
"#,
        );
        assert_eq!(
            version_and_service_pack(sles.version.as_deref(), sles.version_id.as_deref()),
            ("15".to_string(), Some("5".to_string()))
        );

        // The machine this is written on, which it inventories with `Distro::LSB`, where
        // `lsb_release -r` prints the "26.04" that `VERSION_ID` holds.
        let ubuntu = OsRelease::from_string(
            r#"PRETTY_NAME="Ubuntu 26.04 LTS"
NAME="Ubuntu"
VERSION_ID="26.04"
VERSION="26.04 LTS (Resolute Raccoon)"
ID=ubuntu
"#,
        );
        assert_eq!(
            version_and_service_pack(ubuntu.version.as_deref(), ubuntu.version_id.as_deref()),
            ("26.04".to_string(), None)
        );
    }

    /// The seventeen names the zone database links to `UTC`, which is the list
    /// `DateTime::TimeZone` resolves them through for FusionInventory.
    #[test]
    fn it_names_a_utc_machine_utc() {
        for alias in [
            "Etc/UTC",
            "Etc/GMT",
            "Etc/GMT+0",
            "Etc/GMT-0",
            "Etc/GMT0",
            "Etc/Greenwich",
            "Etc/UCT",
            "Etc/Universal",
            "Etc/Zulu",
            "GMT",
            "GMT+0",
            "GMT-0",
            "GMT0",
            "Greenwich",
            "UCT",
            "Universal",
            "Zulu",
        ] {
            assert_eq!(zone_name(alias), "UTC", "{alias}");
        }
        // The name they all link to, which needs no resolving.
        assert_eq!(zone_name("UTC"), "UTC");
    }

    #[test]
    fn it_leaves_every_other_zone_alone() {
        // A zone of its own is reported as it is named.
        for zone in ["Europe/Paris", "America/New_York", "Australia/Eucla"] {
            assert_eq!(zone_name(zone), zone);
        }
        // A link that does not lead to UTC keeps the name the machine is set to, where
        // FusionInventory would report the zone it renames to. A known difference.
        assert_eq!(zone_name("Asia/Calcutta"), "Asia/Calcutta");
        assert_eq!(zone_name("US/Eastern"), "US/Eastern");
        // A zone that only looks like one of the aliases.
        assert_eq!(zone_name("Etc/GMT+1"), "Etc/GMT+1");
        assert_eq!(zone_name("Etc/GMT-14"), "Etc/GMT-14");
        assert_eq!(zone_name("America/Greenwich"), "America/Greenwich");
        // The comparison is on the whole name, and the names are case sensitive.
        assert_eq!(zone_name("utc"), "utc");
        assert_eq!(zone_name(""), "");
    }

    #[test]
    fn it_reads_the_timezone_of_this_machine() {
        // The zone depends on the machine, its shape does not.
        let timezone = Timezone::new().expect("no local timezone");
        assert!(!timezone.name.is_empty());
        let offset = timezone.offset;
        assert!(
            offset.len() == 5
                && matches!(offset.as_bytes()[0], b'+' | b'-')
                && offset[1..].bytes().all(|b| b.is_ascii_digit()),
            "unexpected offset '{offset}'"
        );
    }
}
