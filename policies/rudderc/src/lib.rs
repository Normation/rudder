// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

use std::{
    fs::create_dir_all,
    path::{Path, PathBuf},
};

use anyhow::{Context, Result};

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
    let input = Path::new(TECHNIQUE_SRC);
    let cwd = PathBuf::from(".");
    let target = PathBuf::from(TARGET_DIR);

    match args.command {
        Command::Init => action::init(&cwd, None),
        Command::New { name } => {
            create_dir_all(&name)
                .with_context(|| format!("Failed to create technique directory {}", name))?;
            action::init(&cwd.join(&name), Some(name.clone()))
        }
        Command::Check { library } => action::check(library.as_slice(), input),
        Command::Build { library, output } => {
            let actual_output = output.unwrap_or(target);
            create_dir_all(&actual_output).with_context(|| {
                format!(
                    "Failed to create target directory {}",
                    actual_output.display()
                )
            })?;
            action::build(library.as_slice(), input, actual_output.as_path())
        }
        Command::Lib {
            library,
            output,
            format,
            open,
            stdout,
        } => {
            let actual_output = output.unwrap_or(target);
            action::lib_doc(
                library.as_slice(),
                actual_output.as_path(),
                format,
                open,
                stdout,
            )
        }
    }
}

// Actions
pub mod action {
    use std::{
        fs,
        fs::{create_dir, read_to_string, File},
        io::{self, Write},
        path::{Path, PathBuf},
        process::Command,
    };

    use anyhow::{bail, Context, Result};
    use boon::{Compiler, Schemas};
    use rudder_commons::ALL_TARGETS;
    use serde_json::Value;

    pub use crate::compiler::compile;
    use crate::{
        compiler::metadata,
        doc::{book, Format},
        frontends::methods::read_methods,
        ir::Technique,
        logs::ok_output,
        METADATA_FILE, RESOURCES_DIR, TECHNIQUE, TECHNIQUE_SRC,
    };

    /// Create a technique skeleton
    pub fn init(output: &Path, name: Option<String>) -> Result<()> {
        let mut technique = Technique::default();
        if let Some(n) = name {
            technique.name = n;
        }
        let t = serde_yaml::to_string(&technique)?;
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
    pub fn lib_doc(
        libraries: &[PathBuf],
        output_dir: &Path,
        format: Format,
        open: bool,
        stdout: bool,
    ) -> Result<()> {
        let methods = read_methods(libraries)?;
        // Special case as output is multi-file
        let file_to_open = if format == Format::Html {
            let index = book::render(methods, output_dir)?;
            ok_output("Wrote", index.display());
            Some(index)
        } else {
            let data = format.render(methods)?;
            fs::create_dir_all(output_dir)?;
            let doc_file = output_dir.join(Path::new(TECHNIQUE).with_extension(format.extension()));

            if stdout {
                io::stdout().write_all(data.as_bytes())?;
                None
            } else {
                let mut file = File::create(doc_file.as_path()).with_context(|| {
                    format!("Failed to create output file {}", doc_file.display())
                })?;
                file.write_all(data.as_bytes())?;
                ok_output("Wrote", doc_file.display());

                Some(doc_file)
            }
        };

        // Open in browser
        if let Some(f) = file_to_open {
            if open {
                ok_output("Opening", f.display());
                let _ = Command::new("xdg-open").args([f]).output();
            }
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
        fs::create_dir_all(output_dir)?;

        // Technique implementation
        for target in ALL_TARGETS {
            let policy_file =
                output_dir.join(Path::new(TECHNIQUE).with_extension(target.extension()));
            let mut file = File::create(&policy_file).with_context(|| {
                format!("Failed to create output file {}", policy_file.display())
            })?;
            file.write_all(compile(methods, &policy, *target, input)?.as_bytes())?;
            ok_output("Wrote", policy_file.display());
        }

        // Metadata for the webapp
        let metadata_file = output_dir.join(METADATA_FILE);
        let mut file = File::create(&metadata_file).with_context(|| {
            format!(
                "Failed to create metadata output file {}",
                metadata_file.display()
            )
        })?;
        file.write_all(metadata(methods, &policy, input)?.as_bytes())?;
        ok_output("Wrote", metadata_file.display());

        // Resources folder
        let resources_path = input.parent().unwrap().join(RESOURCES_DIR);
        if resources_path.exists() {
            pub fn copy_recursively(
                source: impl AsRef<Path>,
                destination: impl AsRef<Path>,
            ) -> io::Result<()> {
                fs::create_dir_all(&destination)?;
                for entry in fs::read_dir(source)? {
                    let entry = entry?;
                    let filetype = entry.file_type()?;
                    if filetype.is_dir() {
                        copy_recursively(
                            entry.path(),
                            destination.as_ref().join(entry.file_name()),
                        )?;
                    } else {
                        fs::copy(entry.path(), destination.as_ref().join(entry.file_name()))?;
                    }
                }
                Ok(())
            }
            copy_recursively(&resources_path, output_dir.join(RESOURCES_DIR))?;
            ok_output("Copied", resources_path.display());
        }

        Ok(())
    }
}
