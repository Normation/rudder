// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

// FIXME remove
#![allow(dead_code)]

use anyhow::Result;
use rudder_commons::Target;

use crate::cli::MainArgs;

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
pub fn run(args: MainArgs) -> Result<()> {
    if args.resource_description {
        action::describe(args.library.as_slice(), &args.output.unwrap())
    } else {
        // guess output target
        let target = args.target()?;

        if args.check {
            action::check(args.library.as_slice(), &args.input.unwrap(), target)
        } else {
            action::write(
                args.library.as_slice(),
                &args.input.unwrap(),
                &args.output.unwrap(),
                target,
            )
        }
    }
}

// Actions
pub mod action {
    use std::{
        fs::File,
        io::Write,
        path::{Path, PathBuf},
    };

    use anyhow::{Context, Result};
    use rudder_commons::Target;

    pub use crate::compiler::{compile, methods_description};
    use crate::logs::ok_output;

    /// Describe available resources
    pub fn describe(libraries: &[PathBuf], output: &Path) -> Result<()> {
        let mut file = File::create(output)
            .with_context(|| format!("Failed to create output file {}", output.display()))?;
        file.write_all(methods_description(libraries)?.as_bytes())?;
        ok_output("Wrote", output.display());
        Ok(())
    }

    /// Linter mode
    pub fn check(libraries: &[PathBuf], input: &Path, target: Target) -> Result<()> {
        // For now check = compile.is_ok
        compile(libraries, input, target)?;
        ok_output("Checked", input.display());
        Ok(())
    }

    /// Write output
    pub fn write(libraries: &[PathBuf], input: &Path, output: &Path, target: Target) -> Result<()> {
        let mut file = File::create(output)
            .with_context(|| format!("Failed to create output file {}", input.display()))?;
        file.write_all(compile(libraries, input, target)?.as_bytes())?;
        ok_output("Wrote", output.display());
        Ok(())
    }
}
