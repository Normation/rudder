// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

//! Installed software and pending updates, from the system package manager.

mod apt;
mod dpkg;
mod rpm;
mod yum;
mod zypper;

use anyhow::Result;
use rudder_module_type::os_release::OsRelease;
use serde::Serialize;
use tracing::{debug, info, instrument};

use crate::packages::{apt::AptGet, dpkg::Dpkg, rpm::Rpm, yum::Yum, zypper::Zypper};

/// An installed software, as expected in the `SOFTWARES` section.
///
/// Fields are declared in the order FusionInventory serializes them, to keep both outputs
/// easy to compare.
#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
pub struct Package {
    /// Kept although the server does not read it: the architecture of a package is needed to
    /// tell two builds of the same version apart.
    #[serde(skip_serializing_if = "Option::is_none")]
    arch: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    comments: Option<String>,
    name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    publisher: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    source_name: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    source_version: Option<String>,
    version: String,
}

/// An available update for an installed software, as expected in the `SOFTWAREUPDATES`
/// section.
///
/// Fields are declared in the order FusionInventory serializes them, to keep both outputs
/// easy to compare.
#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
pub struct Update {
    #[serde(skip_serializing_if = "Option::is_none")]
    arch: Option<String>,
    /// The package manager the update was inventoried from.
    from: String,
    /// One of `none`, `security`, `defect` or `enhancement`. Anything else is reported as
    /// `other` by the server.
    #[serde(skip_serializing_if = "Option::is_none")]
    kind: Option<String>,
    name: String,
    /// The repository the update comes from.
    #[serde(skip_serializing_if = "Option::is_none")]
    source: Option<String>,
    version: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    description: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    severity: Option<String>,
    /// Comma separated list
    #[serde(rename = "ID", skip_serializing_if = "Option::is_none")]
    ids: Option<String>,
}

/// The kinds of update the server has a name for.
///
/// It reads anything else as `other`, keeping the value, so an unknown kind loses nothing but
/// is worth naming here when it turns out to be common.
const KINDS: [&str; 4] = ["none", "defect", "security", "enhancement"];

/// The severities the server has a name for, likewise.
const SEVERITIES: [&str; 4] = ["low", "moderate", "high", "critical"];

/// The kind of update a package manager's own word for it means to the server.
///
/// `yum` and `zypper` both classify their updates, in vocabularies of their own that overlap
/// without matching: what `yum` calls `bugfix` and `zypper` calls `recommended` are both the
/// server's `defect`. A word neither we nor the server knows is passed through, to be read as
/// `other`, rather than flattened into `none`, which would claim it is a routine update.
fn kind(name: &str) -> String {
    match name.trim().to_lowercase().as_str() {
        "security" => "security".to_string(),
        // `yum` says bugfix, `zypper` says recommended, the server says defect.
        "bugfix" | "recommended" => "defect".to_string(),
        "enhancement" | "feature" | "optional" => "enhancement".to_string(),
        "newpackage" | "none" | "" => "none".to_string(),
        other => other.to_string(),
    }
}

/// The severity of an update, under the name the server has for it.
///
/// `yum` writes `None` for an update that has no severity, which is not a severity but the
/// absence of one, and is reported as nothing at all. `zypper` adds `important`, which is the
/// server's `high`.
fn severity(name: &str) -> Option<String> {
    match name.trim().to_lowercase().as_str() {
        "none" | "" | "unspecified" => None,
        "important" => Some("high".to_string()),
        other => Some(other.to_string()),
    }
}

pub trait PackageManager {
    /// Whether this package manager is the one managing the local system.
    fn is_available() -> bool;

    fn installed(os_release: &OsRelease) -> Result<Vec<Package>>;
}

pub trait UpdateManager {
    /// Whether this update source is usable on the local system.
    fn is_available() -> bool;

    fn updates() -> Result<Vec<Update>>;
}

/// Inventories installed software and pending updates.
///
/// The two are asked of different tools, and which is available decides: `dpkg` and `rpm` list
/// what is installed, `apt-get`, `yum` and `zypper` what could be. They are tried in that
/// order, and a machine is expected to have one of each at most. A machine with neither is a
/// nominal outcome, not an error: the sections are then left out of the inventory.
///
/// The check is what the machine has installed rather than what `/etc/os-release` says it is,
/// so that a distribution we have never heard of is inventoried as long as it uses a package
/// manager we know.
#[instrument(level = "debug", name = "packages", skip(os_release))]
pub fn inventory(os_release: &OsRelease) -> Result<(Vec<Package>, Vec<Update>)> {
    let installed = if Dpkg::is_available() {
        let installed = Dpkg::installed(os_release)?;
        debug!("Found {} installed packages with dpkg", installed.len());
        installed
    } else if Rpm::is_available() {
        let installed = Rpm::installed(os_release)?;
        debug!("Found {} installed packages with rpm", installed.len());
        installed
    } else {
        info!("No supported package manager found, reporting no installed software");
        vec![]
    };
    let updates = if AptGet::is_available() {
        let updates = AptGet::updates()?;
        debug!("Found {} pending updates with apt", updates.len());
        updates
    } else if Zypper::is_available() {
        // Before yum, as a SUSE machine has both and only zypper knows its repositories.
        let updates = Zypper::updates()?;
        debug!("Found {} pending updates with zypper", updates.len());
        updates
    } else if Yum::is_available() {
        let updates = Yum::updates()?;
        debug!("Found {} pending updates with yum", updates.len());
        updates
    } else {
        info!("No supported update source found, reporting no pending update");
        vec![]
    };
    Ok((installed, updates))
}

#[cfg(test)]
mod tests {
    use pretty_assertions::assert_eq;
    use quick_xml::se::Serializer;
    use serde::Serialize;

    use super::*;

    fn to_xml<T: Serialize>(root: &str, value: &T) -> String {
        let mut out = String::new();
        let mut ser = Serializer::with_root(&mut out, Some(root)).unwrap();
        ser.indent(' ', 2);
        value.serialize(ser).unwrap();
        out
    }

    /// The serialized shape has to stay the one FusionInventory produces, field for field.
    #[test]
    fn it_serializes_a_package_like_fusion_inventory() {
        let package = Package {
            arch: Some("amd64".to_string()),
            comments: None,
            name: "acl".to_string(),
            publisher: Some("Debian".to_string()),
            source_name: Some("acl".to_string()),
            source_version: Some("2.3.1-3".to_string()),
            version: "2.3.1-3".to_string(),
        };
        assert_eq!(
            to_xml("SOFTWARES", &package),
            "<SOFTWARES>\n  \
               <ARCH>amd64</ARCH>\n  \
               <NAME>acl</NAME>\n  \
               <PUBLISHER>Debian</PUBLISHER>\n  \
               <SOURCE_NAME>acl</SOURCE_NAME>\n  \
               <SOURCE_VERSION>2.3.1-3</SOURCE_VERSION>\n  \
               <VERSION>2.3.1-3</VERSION>\n\
             </SOFTWARES>"
        );
    }

    #[test]
    fn it_serializes_an_update_like_fusion_inventory() {
        let update = Update {
            arch: Some("amd64".to_string()),
            from: "apt-get".to_string(),
            kind: Some("none".to_string()),
            name: "base-files".to_string(),
            source: Some("Debian:12.15/oldstable".to_string()),
            version: "12.4+deb12u15".to_string(),
            description: None,
            severity: None,
            ids: None,
        };
        assert_eq!(
            to_xml("SOFTWAREUPDATES", &update),
            "<SOFTWAREUPDATES>\n  \
               <ARCH>amd64</ARCH>\n  \
               <FROM>apt-get</FROM>\n  \
               <KIND>none</KIND>\n  \
               <NAME>base-files</NAME>\n  \
               <SOURCE>Debian:12.15/oldstable</SOURCE>\n  \
               <VERSION>12.4+deb12u15</VERSION>\n\
             </SOFTWAREUPDATES>"
        );
    }
}
