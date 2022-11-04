// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

//! Rudder resource_type represented in YAML format

use std::{fs::read_to_string, path::Path};

use anyhow::{bail, Context, Result};
use log::trace;

use crate::ir::Technique;

pub fn read(input: &Path) -> Result<Technique> {
    let data = read_to_string(input)
        .with_context(|| format!("Failed to read input from {}", input.display()))?;
    let policy: Technique = serde_yaml::from_str(&data)?;
    trace!("Parsed input:\n{:#?}", policy);

    // Stop if unknown format
    if policy.format != 0 {
        bail!("Unknown policy format version: {}", policy.format);
    }
    Ok(policy)
}
