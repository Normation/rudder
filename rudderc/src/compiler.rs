// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

use std::path::{Path, PathBuf};

use anyhow::Result;

use crate::{
    backends::Target,
    frontends::{
        ncf::{method::Method, reader::read_lib},
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

    target.backend().generate(policy)
}
