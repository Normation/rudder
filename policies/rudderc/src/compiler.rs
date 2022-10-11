// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

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

/// Compute the output of the file
pub fn describe_resources(libraries: &[PathBuf]) -> Result<String> {
    let mut methods: Vec<Method> = vec![];
    for library in libraries {
        let mut add = read_lib(library)?;
        let len = add.len();
        methods.append(&mut add);
        ok_output("Read", format!("{} methods ({})", len, library.display()))
    }

    ok_output("Generating", format!("resources description"));

    serde_yaml::to_string(&methods).context("Serializing resources")
}
