// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use std::collections::HashMap;

/// Implementation of Linux package manager interactions.
///
use anyhow::{bail, Result};
use serde::{Deserialize, Serialize};

#[cfg(feature = "apt")]
use crate::package_manager::apt::AptPackageManager;
use crate::{
    output::ResultOutput,
    package_manager::{yum::YumPackageManager, zypper::ZypperPackageManager},
};
use std::str::FromStr;

#[cfg(feature = "apt")]
mod apt;
mod rpm;
mod yum;
mod zypper;

/// Details of a package (installed or available) in a package manager context
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
pub struct PackageList {
    // This structure allows querying the presence of a package efficiently
    pub(crate) inner: HashMap<PackageId, PackageInfo>,
}

/// Details of a package (installed or available) in a package manager context
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub(crate) struct PackageInfo {
    pub(crate) version: String,
    pub(crate) from: String,
    pub(crate) source: PackageManager,
}

impl PackageList {
    pub fn new(list: HashMap<PackageId, PackageInfo>) -> Self {
        Self { inner: list }
    }

    pub fn diff(&self, new: Self) -> Vec<PackageDiff> {
        // FIXME: check package managers
        let mut changes = vec![];

        for (p, info) in &self.inner {
            if !new.inner.contains_key(p) {
                let action = PackageDiff {
                    id: p.clone(),
                    old_version: Some(info.version.clone()),
                    new_version: None,
                    action: PackageAction::Removed,
                };
                changes.push(action);
            }
        }

        for (p, info) in new.inner {
            match self.inner.get(&p) {
                None => {
                    let action = PackageDiff {
                        id: p.clone(),
                        new_version: Some(info.version),
                        old_version: None,
                        action: PackageAction::Added,
                    };
                    changes.push(action);
                }
                Some(i) if i.version == info.version => continue,
                Some(i) => {
                    let action = PackageDiff {
                        id: p.clone(),
                        new_version: Some(info.version),
                        old_version: Some(i.version.clone()),
                        action: PackageAction::Updated,
                    };
                    changes.push(action);
                }
            }
        }

        changes
    }
}

#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub struct PackageDiff {
    id: PackageId,
    #[serde(skip_serializing_if = "Option::is_none")]
    old_version: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    new_version: Option<String>,
    action: PackageAction,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum PackageAction {
    Removed,
    Added,
    Updated,
}

/// The description of a package to manage
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct PackageSpec {
    name: String,
    // None means any
    #[serde(skip_serializing_if = "Option::is_none")]
    version: Option<String>,
    // None means any
    #[serde(skip_serializing_if = "Option::is_none")]
    architecture: Option<String>,
}

impl PackageSpec {
    #[allow(dead_code)]
    pub fn new(name: String, version: Option<String>, architecture: Option<String>) -> Self {
        Self {
            name,
            version,
            architecture,
        }
    }
}

/// We consider packages with the same name but different arch as different packages.
///
/// All other package properties are considered variable (version, repo, etc.).
#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
pub struct PackageId {
    name: String,
    /// We don't need to know about the architecture, we use each package manager's
    /// arch names as is.
    arch: String,
}

// FIXME serialize

impl PackageId {
    pub fn new(name: String, arch: String) -> Self {
        Self { name, arch }
    }
}

#[derive(Copy, Clone, Debug, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "lowercase")]
pub enum PackageManager {
    #[serde(alias = "dnf")]
    #[default]
    Yum,
    #[cfg(feature = "apt")]
    Apt,
    Zypper,
    Rpm,
}

impl FromStr for PackageManager {
    type Err = anyhow::Error;

    fn from_str(s: &str) -> Result<Self> {
        Ok(match s {
            "yum" => PackageManager::Yum,
            "dnf" => PackageManager::Yum,
            #[cfg(feature = "apt")]
            "apt" => PackageManager::Apt,
            "zypper" => PackageManager::Zypper,
            "rpm" => PackageManager::Rpm,
            _ => bail!("Unknown package manager: {}", s),
        })
    }
}

impl PackageManager {
    pub fn get(self) -> Result<Box<dyn LinuxPackageManager>> {
        Ok(match self {
            PackageManager::Yum => Box::new(YumPackageManager::new()),
            #[cfg(feature = "apt")]
            PackageManager::Apt => Box::new(AptPackageManager::new()?),
            PackageManager::Zypper => Box::new(ZypperPackageManager::new()),
            _ => bail!("This package manager does not provide patch management features"),
        })
    }
}

/// A generic interface of a Linux package manager
pub trait LinuxPackageManager {
    /// Update the package cache
    fn update_cache(&mut self) -> ResultOutput<()> {
        ResultOutput::new(Ok(()))
    }

    /// List installed packages
    ///
    /// It doesn't use a cache and queries the package manager directly.
    fn list_installed(&mut self) -> ResultOutput<PackageList>;

    /// Apply all available upgrades
    fn full_upgrade(&mut self) -> ResultOutput<()>;

    /// Apply all security upgrades
    fn security_upgrade(&mut self) -> ResultOutput<()>;

    /// Upgrade specific packages
    fn upgrade(&mut self, packages: Vec<PackageSpec>) -> ResultOutput<()>;

    /// Is a reboot pending?
    fn reboot_pending(&self) -> ResultOutput<bool>;

    /// List the services to restart
    fn services_to_restart(&self) -> ResultOutput<Vec<String>>;
}

#[cfg(test)]
mod tests {
    use pretty_assertions::assert_eq;

    use super::*;

    #[test]
    fn it_diffs_package_lists() {
        let mut old = HashMap::new();
        old.insert(
            PackageId::new("mesa-vulkan-drivers".to_string(), "x86_64".to_string()),
            PackageInfo {
                version: "22.1.2-1.fc36".to_string(),
                from: "".to_string(),
                source: PackageManager::Yum,
            },
        );
        old.insert(
            PackageId::new("mesa-libxatracker".to_string(), "x86_64".to_string()),
            PackageInfo {
                version: "22.1.2-1.fc36".to_string(),
                from: "".to_string(),
                source: PackageManager::Yum,
            },
        );
        old.insert(
            PackageId::new("gtksourceview5".to_string(), "x86_64".to_string()),
            PackageInfo {
                version: "5.4.2-1.fc36".to_string(),
                from: "".to_string(),
                source: PackageManager::Yum,
            },
        );
        old.insert(
            PackageId::new("gnome-software".to_string(), "x86_64".to_string()),
            PackageInfo {
                version: "42.2-4.fc36".to_string(),
                from: "".to_string(),
                source: PackageManager::Yum,
            },
        );
        old.insert(
            PackageId::new("google-chrome-stable".to_string(), "x86_64".to_string()),
            PackageInfo {
                version: "103.0.5060.53-1".to_string(),
                from: "".to_string(),
                source: PackageManager::Yum,
            },
        );

        let mut new = HashMap::new();
        new.insert(
            PackageId::new("libxslt".to_string(), "x86_64".to_string()),
            PackageInfo {
                version: "1.1.35-2.fc36".to_string(),
                from: "".to_string(),
                source: PackageManager::Yum,
            },
        );
        new.insert(
            PackageId::new("mesa-libxatracker".to_string(), "x86_64".to_string()),
            PackageInfo {
                version: "22.1.2-1.fc36".to_string(),
                from: "".to_string(),
                source: PackageManager::Yum,
            },
        );
        new.insert(
            PackageId::new("gtksourceview5".to_string(), "x86_64".to_string()),
            PackageInfo {
                version: "5.5.2-1.fc36".to_string(),
                from: "".to_string(),
                source: PackageManager::Yum,
            },
        );
        new.insert(
            PackageId::new("gnome-software".to_string(), "x86_64".to_string()),
            PackageInfo {
                version: "42.2-4.fc36".to_string(),
                from: "".to_string(),
                source: PackageManager::Yum,
            },
        );
        new.insert(
            PackageId::new("google-chrome-stable".to_string(), "x86_64".to_string()),
            PackageInfo {
                version: "103.0.5060.53-1".to_string(),
                from: "".to_string(),
                source: PackageManager::Yum,
            },
        );

        let old_p = PackageList { inner: old };
        let new_p = PackageList { inner: new };

        let mut reference = vec![
            PackageDiff {
                id: PackageId::new("mesa-vulkan-drivers".to_string(), "x86_64".to_string()),
                old_version: Some("22.1.2-1.fc36".to_string()),
                new_version: None,
                action: PackageAction::Removed,
            },
            PackageDiff {
                id: PackageId::new("gtksourceview5".to_string(), "x86_64".to_string()),
                old_version: Some("5.4.2-1.fc36".to_string()),
                new_version: Some("5.5.2-1.fc36".to_string()),
                action: PackageAction::Updated,
            },
            PackageDiff {
                id: PackageId::new("libxslt".to_string(), "x86_64".to_string()),
                old_version: None,
                new_version: Some("1.1.35-2.fc36".to_string()),
                action: PackageAction::Added,
            },
        ];

        let mut result = old_p.diff(new_p);
        result.sort();
        reference.sort();

        assert_eq!(result, reference);
    }
}
