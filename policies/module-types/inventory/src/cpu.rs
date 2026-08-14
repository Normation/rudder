// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

//! The `CPUS` section: what the machine computes with.

use serde::Serialize;
use sysinfo::System;

#[derive(Debug, PartialEq, Serialize)]
#[serde(rename_all = "UPPERCASE")]
pub struct Cpu {
    name: String,
    manufacturer: String,
}

/// One entry per logical CPU, out of what `sysinfo` decodes.
///
/// The `System` has to have had its CPUs refreshed.
pub fn inventory(sys: &System) -> Vec<Cpu> {
    sys.cpus()
        .iter()
        .map(|c| Cpu {
            name: c.brand().to_string(),
            manufacturer: c.vendor_id().to_string(),
        })
        .collect()
}
