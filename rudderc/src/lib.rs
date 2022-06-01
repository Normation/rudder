// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

// FIXME remove
#![allow(dead_code)]

use anyhow::Result;

pub use crate::compiler::compile;
use crate::{backends::Target, cli::Args};

pub mod backends;
pub mod cli;
pub mod compiler;
pub mod frontends;
pub mod ir;
pub mod logs;

/// Main entry point for rudderc
///
/// # Error management
///
/// The current process is to stop at first error, and move it up to `main()` where it will
/// be displayed.
pub fn run(args: Args) -> Result<()> {
    let target = args.target()?;

    if args.check {
        action::check(&args.input, target)
    } else {
        action::write(&args.input, &args.output, target)
    }
}

// Actions
pub mod action {
    use std::{fs::File, io::Write, path::Path};

    use anyhow::{Context, Result};

    pub use crate::compiler::compile;
    use crate::{backends::Target, logs::ok_output};

    /// Linter mode
    pub fn check(input: &Path, target: Target) -> Result<()> {
        // For now check = compile.is_ok
        compile(input, target)?;
        ok_output("Checked", input.display());
        Ok(())
    }

    /// Write output
    pub fn write(input: &Path, output: &Path, target: Target) -> Result<()> {
        let mut file = File::create(output)
            .with_context(|| format!("Failed to create output file {}", input.display()))?;
        file.write_all(compile(input, target)?.as_bytes())?;
        ok_output("Wrote", output.display());
        Ok(())
    }
}
