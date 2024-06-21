// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use anyhow::Result;

mod apt;
mod dpkg;
mod rpm;
mod yum;
mod zypper;

/// Designates an installed package in a package manager context
#[derive(Debug, PartialEq, Eq, Clone)]
struct Package {
    name: String,
    version: String,
    // here, we use the value used by the package manager, and do not try to
    // align on common values.
    architecture: String,
    from: String,
    source: PackageManager,
}

pub struct PackageList {
    list: Vec<Package>,
}

#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub enum PackageManager {
    Yum,
    Apt,
    Zypper,
    Rpm,
    Dpkg,
}

pub trait PackageManagerImpl {
    fn list_installed(&self) -> Result<PackageList>;

    fn full_upgrade(&self) -> Result<()>;

    fn upgrade(&self) -> Result<()>;
}
