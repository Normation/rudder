// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

use anyhow::Result;
use clap::{error::ErrorKind, CommandFactory};
use rudder_commons::Target;

use crate::cli::MainArgs;

pub mod backends;
pub mod cli;
pub mod compiler;
mod doc;
pub mod frontends;
pub mod ir;
pub mod logs;

/// We want to only compile the regex once
///
/// Use once_cell as showed in its documentation
/// https://docs.rs/once_cell/1.2.0/once_cell/index.html#building-block
macro_rules! regex {
    ($re:literal $(,)?) => {{
        static RE: once_cell::sync::OnceCell<regex::Regex> = once_cell::sync::OnceCell::new();
        RE.get_or_init(|| regex::Regex::new($re).unwrap())
    }};
}

pub(crate) use regex;

/// Main entry point for rudderc
///
/// # Error management
///
/// The current process is to stop at first error, and move it up to `main()` where it will
/// be displayed.
pub fn run(args: MainArgs) -> Result<()> {
    // guess output target
    let target = args.target()?;

    if args.modules {
        action::describe(args.library.as_slice(), args.output.as_ref(), target)
    } else {
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
            let output_metadata = args.metadata.as_ref();
            action::write(
                args.library.as_slice(),
                &input,
                &output,
                target,
                output_metadata,
            )
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

    use anyhow::{bail, Context, Result};
    use boon::{Compiler, Schemas};
    use log::error;
    use rudder_commons::Target;
    use serde_json::Value;

    pub use crate::compiler::{compile, methods_description};
    use crate::{
        compiler::{metadata, methods_documentation},
        logs::ok_output,
    };

    /// Describe available modules
    pub fn describe(libraries: &[PathBuf], output: Option<&PathBuf>, target: Target) -> Result<()> {
        let data = match target {
            Target::Metadata => methods_description(libraries)?,
            Target::Docs => methods_documentation(libraries)?,
            _ => bail!("modules flag requires a metadata target"),
        };

        if let Some(out) = output {
            let mut file = File::create(out)
                .with_context(|| format!("Failed to create output file {}", out.display()))?;
            file.write_all(data.as_bytes())?;
            ok_output("Wrote", out.display());
        } else {
            // If not output, write on stdout
            io::stdout().write_all(data.as_bytes())?;
        }
        Ok(())
    }

    /// Linter mode, check JSON schema compliance and ability to compile
    pub fn check(libraries: &[PathBuf], input: &Path, target: Target) -> Result<()> {
        // JSON schema validity
        //
        // load schema first
        let schema_url = "https://docs.rudder.io/schemas/technique.schema.json";
        let schema: Value = serde_json::from_str(include_str!("./technique.schema.json")).unwrap();
        let mut schemas = Schemas::new();
        let mut compiler = Compiler::new();
        compiler.add_resource(schema_url, schema).unwrap();
        let sch_index = compiler.compile(schema_url, &mut schemas).unwrap();
        // the load technique file
        let instance: Value = serde_yaml::from_reader(File::open(input)?)?;
        // ... and validate
        let result = schemas.validate(&instance, sch_index);
        if let Err(error) = result {
            error!("{error:#}");
            // :# gives error details
            bail!("{error}");
        }
        // Compilation test
        compile(libraries, input, target)?;
        //
        ok_output("Checked", input.display());
        Ok(())
    }

    /// Write output
    pub fn write(
        libraries: &[PathBuf],
        input: &Path,
        output: &Path,
        target: Target,
        output_metadata: Option<&PathBuf>,
    ) -> Result<()> {
        let mut file = File::create(output)
            .with_context(|| format!("Failed to create output file {}", input.display()))?;
        file.write_all(compile(libraries, input, target)?.as_bytes())?;
        ok_output("Wrote", output.display());

        if let Some(metadata_file) = output_metadata {
            let mut file = File::create(metadata_file).with_context(|| {
                format!("Failed to create metadata output file {}", input.display())
            })?;
            file.write_all(metadata(input)?.as_bytes())?;
            ok_output("Wrote", metadata_file.display());
        }

        Ok(())
    }
}
