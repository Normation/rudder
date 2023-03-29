// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

use std::path::{Path, PathBuf};

use anyhow::Result;

use crate::cli::{Command, MainArgs};

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

pub const TARGET_DIR: &str = "target";
pub const TECHNIQUE: &str = "technique";
pub const TECHNIQUE_SRC: &str = "technique.yml";
pub const METADATA_FILE: &str = "metadata.xml";
pub const RESOURCES_DIR: &str = "resources";

/// Main entry point for rudderc
///
/// # Error management
///
/// The current process is to stop at first error, and move it up to `main()` where it will
/// be displayed.
pub fn run(args: MainArgs) -> Result<()> {
    let input = Path::new(TARGET_DIR).join(TECHNIQUE_SRC);
    let cwd = PathBuf::from(".");

    match args.command {
        Command::Init => action::init(&cwd),
        Command::Check { library } => action::check(library.as_slice(), input.as_path()),
        Command::Build { library, output } => {
            let actual_output = output.unwrap_or(cwd);
            action::build(library.as_slice(), input.as_path(), actual_output.as_path())
        }
        Command::LibDoc {
            library,
            output,
            format,
            open: _,
        } => action::lib_doc(library.as_slice(), output, format),
    }
}

// Actions
pub mod action {
    use std::{
        fs::{create_dir, read_to_string, File},
        io::{self, Write},
        path::{Path, PathBuf},
    };

    use anyhow::{bail, Context, Result};
    use boon::{Compiler, Schemas};
    use rudder_commons::ALL_TARGETS;
    use serde_json::Value;

    pub use crate::compiler::compile;
    use crate::{
        compiler::metadata, doc::Format, frontends::methods::read_methods, ir::Technique,
        logs::ok_output, METADATA_FILE, RESOURCES_DIR, TECHNIQUE, TECHNIQUE_SRC,
    };

    /// Create a technique skeleton
    pub fn init(output: &Path) -> Result<()> {
        let t = serde_yaml::to_string(&Technique::default())?;
        let tech_path = output.join(TECHNIQUE_SRC);
        let mut file = File::create(tech_path.as_path())
            .with_context(|| format!("Failed to create technique file {}", tech_path.display()))?;
        file.write_all(t.as_bytes())?;
        let resources_dir = output.join(RESOURCES_DIR);
        create_dir(resources_dir.as_path()).with_context(|| {
            format!("Failed to create resources dir {}", resources_dir.display())
        })?;
        ok_output("Wrote", tech_path.display());
        Ok(())
    }

    /// Describe available modules
    pub fn lib_doc(libraries: &[PathBuf], output: Option<PathBuf>, format: Format) -> Result<()> {
        let data = format.render(read_methods(libraries)?)?;
        if let Some(out) = output {
            let mut file = File::create(out.as_path())
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
    pub fn check(libraries: &[PathBuf], input: &Path) -> Result<()> {
        let policy = read_to_string(input)
            .with_context(|| format!("Failed to read input from {}", input.display()))?;
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
        let instance: Value = serde_yaml::from_str(&policy)?;
        // ... and validate
        let result = schemas.validate(&instance, sch_index);
        if let Err(error) = result {
            // :# gives error details
            bail!("{error:#}");
        }
        // Compilation test
        let methods = read_methods(libraries)?;
        for target in ALL_TARGETS {
            compile(methods, &policy, *target, input)?;
        }
        //
        ok_output("Checked", input.display());
        Ok(())
    }

    /// Write output
    pub fn build(libraries: &[PathBuf], input: &Path, output_dir: &Path) -> Result<()> {
        let policy = read_to_string(input)
            .with_context(|| format!("Failed to read input from {}", input.display()))?;
        let methods = read_methods(libraries)?;

        for target in ALL_TARGETS {
            let policy_file =
                output_dir.join(Path::new(TECHNIQUE).with_extension(target.extension()));
            let mut file = File::create(&policy_file).with_context(|| {
                format!("Failed to create output file {}", policy_file.display())
            })?;
            file.write_all(compile(methods, &policy, *target, input)?.as_bytes())?;
            ok_output("Wrote", policy_file.display());
        }

        let metadata_file = output_dir.join(METADATA_FILE);
        let mut file = File::create(&metadata_file).with_context(|| {
            format!(
                "Failed to create metadata output file {}",
                metadata_file.display()
            )
        })?;
        file.write_all(metadata(methods, &policy, input)?.as_bytes())?;
        ok_output("Wrote", metadata_file.display());

        Ok(())
    }
}
