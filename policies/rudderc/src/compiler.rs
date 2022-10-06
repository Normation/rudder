// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

use std::collections::HashMap;
use std::path::{Path, PathBuf};

use anyhow::{Context, Result};
use rudder_commons::Target;

use crate::{
    backends::backend,
    frontends::{
        methods::{method::Method, reader::read_lib},
        yaml,
    },
    logs::ok_output,
};

/// Compute the output of the file
pub fn compile(libraries: &[PathBuf], input: &Path, target: Target) -> Result<String> {
    let policy = yaml::read(input)?;

    let mut methods: Vec<Method> = vec![];
    for library in libraries {
        let mut add = read_lib(library)?;
        let len = add.len();
        methods.append(&mut add);
        ok_output("Read", format!("{} methods ({})", len, library.display()))
    }

    ok_output(
        "Compiling",
        format!(
            "{} v{} [{}] ({})",
            policy.name,
            policy.version,
            target,
            input.display()
        ),
    );

    // TODO checks and optimizations here

    backend(target).generate(policy)
}

/// Compute the output of the JSON file for the webapp
///
/// It replaces the legacy `generic_methods.json` produced by `ncf.py`.
pub fn methods_description(libraries: &[PathBuf]) -> Result<String> {
    let mut methods: Vec<Method> = vec![];
    for library in libraries {
        let mut add = read_lib(library)?;
        let len = add.len();
        methods.append(&mut add);
        ok_output("Read", format!("{} methods ({})", len, library.display()))
    }

    // The webapp expects a map
    let mut res = HashMap::new();
    for method in methods {
        res.insert(method.bundle_name.clone(), method);
    }

    ok_output("Generating", "resources description".to_owned());

    // FIXME: sort output to limit changes
    serde_json::to_string_pretty(&res).context("Serializing resources")
}
