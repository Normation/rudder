// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use std::path::PathBuf;

use anyhow::{bail, Result};
use rudder_commons::methods::{self, Methods};
use tracing::trace;

use crate::ir::Technique;

/// Rudder technique represented in YAML file
pub fn read(input: &str) -> Result<Technique> {
    let policy: Technique = serde_yaml::from_str(input)?;
    trace!("Parsed input:\n{:#?}", policy);

    // Stop if unknown format
    if policy.format != 0 {
        bail!("Unknown policy format version: {}", policy.format);
    }
    Ok(policy)
}

#[cfg(feature = "embedded-lib")]
fn read_static_methods() -> &'static Methods {
    let methods = include_str!("methods.json");
    let methods: Methods = serde_json::from_str(methods).unwrap();
    let methods = Box::new(methods);
    let methods: &'static mut Methods = Box::leak(methods);
    methods
}

pub fn read_methods(libraries: &[PathBuf]) -> Result<&'static Methods> {
    #[cfg(feature = "embedded-lib")]
    {
        let static_methods = read_static_methods();
        if libraries.is_empty() {
            Ok(static_methods)
        } else {
            methods::read(libraries)
        }
    }
    #[cfg(not(feature = "embedded-lib"))]
    methods::read(libraries)
}
