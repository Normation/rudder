// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use std::path::PathBuf;

use anyhow::Result;
use format_serde_error::SerdeError;
use rudder_commons::methods::{self, Methods};
use tracing::{error, trace};

use crate::{
    compiler::user_error,
    ir::{technique::TECHNIQUE_FORMAT_VERSION, Technique},
};

/// Rudder technique represented in YAML file
pub fn read(input: &str) -> Result<Technique> {
    // FIXME: errors in items all point to the `items` first line to to the way
    // the untagged enum is deserialized.
    //
    // Refs:
    // https://github.com/dtolnay/serde-yaml/issues/128
    // https://users.rust-lang.org/t/serde-untagged-enum-ruins-precise-errors/54128/3?u=amousset
    // https://github.com/faradayio/openapi-interfaces/issues/28
    //
    // We need to find a way to pass line numbers, or just deserialized manually.
    let policy: Technique =
        serde_yaml::from_str(input).map_err(|err| SerdeError::new(input.to_string(), err))?;

    trace!("Parsed input:\n{:#?}", policy);

    // Stop if unknown format
    if policy.format != TECHNIQUE_FORMAT_VERSION {
        error!("Unknown policy format version: {}", policy.format);
        user_error()
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
