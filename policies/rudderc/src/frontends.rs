// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use std::path::PathBuf;

use anyhow::Result;
use rudder_commons::methods::{self, Methods};
use tracing::{error, trace};

use crate::{
    compiler::user_error,
    ir::{
        technique::{DeserTechnique, TECHNIQUE_FORMAT_VERSION},
        Technique,
    },
};

/// Rudder technique represented in YAML file
pub fn read(input: &str) -> Result<Technique> {
    // Here we do the parsing in two steps:
    //
    // * A first pass using serde and more general "Deser*" structs, on order to get proper error messages
    //   not permitting with an untagged enum.
    // * A second manual conversion to get the precise type.
    let policy: DeserTechnique =
        serde_yaml_ng::from_str(input)?;
    let policy = policy.to_technique()?;

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
