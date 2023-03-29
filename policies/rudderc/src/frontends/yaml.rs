// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

//! Rudder module_type represented in YAML format

use std::{fs::read_to_string, path::Path};

use anyhow::{bail, Context, Result};
use log::trace;

use crate::ir::Technique;

pub fn read(input: &str) -> Result<Technique> {
    let policy: Technique = serde_yaml::from_str(input)?;
    trace!("Parsed input:\n{:#?}", policy);

    // Stop if unknown format
    if policy.format != 0 {
        bail!("Unknown policy format version: {}", policy.format);
    }
    Ok(policy)
}
