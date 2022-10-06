// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

// FIXME remove
#![allow(dead_code)]

use anyhow::Result;
use clap::error::ErrorKind;
use clap::CommandFactory;
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
    if args.methods_description {
        action::describe(args.library.as_slice(), args.output.as_ref())
    } else {
        // guess output target
        let target = args.target()?;
        let input = match args.input {
            Some(input) => input,
            None => {
                let mut cmd = MainArgs::command();
                cmd.error(ErrorKind::MissingRequiredArgument, "Missing input path")
                    .exit()
            }
        };

        if args.check {
            action::check(args.library.as_slice(), &input, target)
        } else {
            let output = match args.output {
                Some(output) => output,
                None => {
                    let mut cmd = MainArgs::command();
                    cmd.error(ErrorKind::MissingRequiredArgument, "Missing output path")
                        .exit()
                }
            };
            action::write(args.library.as_slice(), &input, &output, target)
        }
    }
}

// Actions
pub mod action {
    use std::{
        fs::File,
        io::{self, Write},
        path::{Path, PathBuf},
    };

    use anyhow::{Context, Result};
    use rudder_commons::Target;

    pub use crate::compiler::{compile, methods_description};
    use crate::logs::ok_output;

    /// Describe available resources
    pub fn describe(libraries: &[PathBuf], output: Option<&PathBuf>) -> Result<()> {
        let description_json = methods_description(libraries)?;

        if let Some(out) = output {
            let mut file = File::create(out)
                .with_context(|| format!("Failed to create output file {}", out.display()))?;
            file.write_all(description_json.as_bytes())?;
            ok_output("Wrote", out.display());
        } else {
            // If not output, write on stdout
            io::stdout().write_all(description_json.as_bytes())?;
        }

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
