// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

//! Rudder resource represented in YAML format

use std::{fs::read_to_string, path::Path};

use anyhow::{anyhow, Context, Result};
use log::trace;

use crate::ir::Policy;

pub fn read(input: &Path) -> Result<Policy> {
    let data = read_to_string(input)
        .with_context(|| format!("Failed to read input from {}", input.display()))?;
    let policy: Policy = serde_yaml::from_str(&data)?;
    trace!("Parsed input:\n{:#?}", policy);

    // Stop if unknown format
    if policy.format != 0 {
        return Err(anyhow!("Unknown policy format version: {}", policy.format));
    }
    Ok(policy)
}
