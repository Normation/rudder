// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use crate::package_manager::dpkg::Dpkg;
/// Implementation of Linux package manager interactions.
///
/// Used both for campaigns and simple package promises.
use anyhow::Result;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

mod apt;
mod dpkg;
mod rpm;
mod yum;
mod zypper;

/// Details of a package (installed or available) in a package manager context
#[derive(Clone, Debug, Serialize, Deserialize)]
struct PackageList {
    // This structure allows querying the presence of a package efficiently
    inner: HashMap<PackageId, PackageInfo>,
}

/// Details of a package (installed or available) in a package manager context
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
struct PackageInfo {
    version: String,
    from: String,
    source: PackageManager,
}

impl PackageList {
    pub fn diff(&self, new: Self) -> Vec<PackageDiff> {
        // FIXME: check package managers
        let mut changes = vec![];

        for (p, info) in self.inner {
            if !new.inner.contains_key(&p) {
                let action = PackageDiff {
                    id: p.clone(),
                    old_version: Some(info.version),
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

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub struct PackageDiff {
    id: PackageId,
    #[serde(skip_serializing_if = "Option::is_none")]
    old_version: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    new_version: Option<String>,
    action: PackageAction,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
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
    version: Option<String>,
    // None means any
    architecture: Option<String>,
}

/// We consider packages with the same name but different arch as different packages.
///
/// All other package properties are considered variable (version, repo, etc.).
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize, Hash)]
pub struct PackageId {
    name: String,
    /// We don't need to know about the architecture, we use each package manager's
    /// arch names as is.
    arch: String,
}

impl PackageId {
    pub fn new(name: String, arch: String) -> Self {
        Self { name, arch }
    }
}

#[derive(Copy, Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum PackageManager {
    Yum,
    Apt,
    Zypper,
    Rpm,
    Dpkg,
}

/// Generic implementation of a Linux package manager
pub trait LinuxPackageManager {
    /// List installed packages
    fn list_installed(&self) -> Result<PackageList>;

    /// Apply all available upgrades
    fn full_upgrade(&self) -> Result<()>;

    /// Apply all security upgrades
    fn security_upgrade(&self) -> Result<()>;

    /// Upgrade specific packages
    fn upgrade(&self, packages: Vec<PackageSpec>) -> Result<()>;
}

#[cfg(test)]
mod tests {
    use super::*;
    use pretty_assertions::assert_eq;

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

        let reference = vec![
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

        assert_eq!(old_p.diff(new_p), reference);
    }
}
