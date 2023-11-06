// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Normation SAS

mod yum;
mod apt;
mod zypper;

#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub enum PackageManager {
    Yum,
    Apt,
    Zypper,
}
