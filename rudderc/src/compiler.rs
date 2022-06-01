// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

use std::path::Path;

use anyhow::Result;

use crate::{backends::Target, frontends::yaml, logs::ok_output};

/// Compute the output of the file
pub fn compile(input: &Path, target: Target) -> Result<String> {
    let policy = yaml::read(input)?;
    // TODO read stdlib

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
