// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

use std::{
    env::set_current_dir,
    fs::create_dir_all,
    path::{Path, PathBuf},
};

#[cfg(not(feature = "embedded-lib"))]
use anyhow::bail;
use anyhow::{anyhow, Context, Result};
#[cfg(not(feature = "embedded-lib"))]
use log::debug;

use crate::cli::{Command, MainArgs};

pub mod backends;
pub mod cli;
pub mod compiler;
mod doc;
pub mod frontends;
pub mod ir;
pub mod logs;
pub mod test;

pub const TARGET_DIR: &str = "target";
pub const TESTS_DIR: &str = "tests";
pub const TECHNIQUE: &str = "technique";
pub const TECHNIQUE_SRC: &str = "technique.yml";
pub const METADATA_FILE: &str = "metadata.xml";
pub const RESOURCES_DIR: &str = "resources";

/// Where to read the library if no path were provided
pub const DEFAULT_LIB_PATH: &str = "/var/rudder/ncf/";

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

    #[cfg(feature = "embedded-lib")]
    fn check_libraries(parameters: Vec<PathBuf>) -> Result<Vec<PathBuf>> {
        Ok(parameters)
    }

    // Check libraries and apply default value if relevant
    #[cfg(not(feature = "embedded-lib"))]
    fn check_libraries(parameters: Vec<PathBuf>) -> Result<Vec<PathBuf>> {
        Ok(if parameters.is_empty() {
            let path = PathBuf::from(DEFAULT_LIB_PATH);
            if path.exists() {
                debug!(
                    "No library path provided but the default '{}' exists, using it.",
                    DEFAULT_LIB_PATH
                );
                vec![path]
            } else {
                bail!(
                    "No library path provided and default path '{}' does not exist.",
                    DEFAULT_LIB_PATH
                );
            }
        } else {
            parameters
        })
    }

    if let Some(cwd) = args.directory {
        // Also support being passed the technique.yml file
        if cwd.ends_with(TECHNIQUE_SRC) {
            set_current_dir(
                cwd.parent().ok_or_else(|| {
                    anyhow!("Could not open {} technique directory", cwd.display())
                })?,
            )?;
        } else {
            set_current_dir(&cwd)?;
        }
    }

    match args.command {
        Command::Init => action::init(&cwd, None),
        Command::New { name } => {
            create_dir_all(&name)
                .with_context(|| format!("Failed to create technique directory {}", name))?;
            action::init(&cwd.join(&name), Some(name))
        }
        Command::Clean => action::clean(target.as_path()),
        Command::Check { library } => {
            let library = check_libraries(library)?;
            action::check(library.as_slice(), input)
        }
        Command::Build {
            library,
            output,
            standalone,
            store_ids,
        } => {
            let library = check_libraries(library)?;
            let actual_output = output.unwrap_or(target);
            action::build(
                library.as_slice(),
                input,
                actual_output.as_path(),
                standalone,
                store_ids,
            )
        }
        Command::Test { library, filter } => {
            let library = check_libraries(library)?;
            action::build(library.as_slice(), input, target.as_path(), true, false)?;
            action::test(
                target.join("technique.cf").as_path(),
                Path::new(TESTS_DIR),
                library.as_slice(),
                filter,
            )
        }
        Command::Lib {
            library,
            output,
            format,
            open,
            stdout,
        } => {
            let library = check_libraries(library)?;
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
        fs::{self, create_dir, create_dir_all, read_to_string, remove_dir_all, File},
        io::{self, Write},
        path::{Path, PathBuf},
        process::Command,
    };

    use anyhow::{bail, Context, Result};
    use boon::{Compiler, Schemas};
    use rudder_commons::{logs::ok_output, ALL_TARGETS};
    use serde_json::Value;

    pub use crate::compiler::compile;
    use crate::{
        backends::unix::cfengine::cf_agent,
        compiler::{metadata, read_technique},
        doc::{book, Format},
        frontends::read_methods,
        ir::Technique,
        test::TestCase,
        METADATA_FILE, RESOURCES_DIR, TARGET_DIR, TECHNIQUE, TECHNIQUE_SRC, TESTS_DIR,
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
        let tests_dir = output.join(TESTS_DIR);
        create_dir(tests_dir.as_path())
            .with_context(|| format!("Failed to create tests dir {}", tests_dir.display()))?;
        ok_output("Wrote", tech_path.display());
        Ok(())
    }

    /// Clean the generated files
    pub fn clean(target: &Path) -> Result<()> {
        if target.exists() {
            remove_dir_all(target).with_context(|| {
                format!("Failed to clean generated files from {}", target.display())
            })?;
            ok_output("Cleaned", target.display());
        }
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
            create_dir_all(output_dir)?;
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
        let policy_str = read_to_string(input)
            .with_context(|| format!("Failed to read input from {}", input.display()))?;
        let policy = read_technique(methods, &policy_str)?;
        for target in ALL_TARGETS {
            compile(policy.clone(), *target, input, false)?;
        }
        //
        ok_output("Checked", input.display());
        Ok(())
    }

    /// Run a test
    pub fn test(
        technique_file: &Path,
        test_dir: &Path,
        libraries: &[PathBuf],
        filter: Option<String>,
    ) -> Result<()> {
        // Run everything relatively to the test directory
        // Collect test cases
        let mut cases = vec![];
        if test_dir.exists() {
            for entry in fs::read_dir(test_dir)? {
                let e = entry?;
                let name = e.file_name().into_string().unwrap();
                if e.file_type()?.is_file() && name.ends_with(".yml") {
                    if let Some(ref f) = filter {
                        // Filter by file name
                        if e.path().file_stem().unwrap().to_string_lossy().contains(f) {
                            cases.push(e.path())
                        }
                    } else {
                        cases.push(e.path())
                    }
                }
            }
        }

        ok_output("Running", format!("{} test(s)", cases.len()));
        for case_path in cases {
            ok_output("Testing", case_path.to_string_lossy());
            let yaml = read_to_string(&case_path)?;
            let case: TestCase = serde_yaml::from_str(&yaml)?;
            // Run test setup
            case.setup(test_dir)?;
            // Run the technique
            ok_output(
                "Running",
                format!(
                    "technique test with parameters from '{}'",
                    case_path.display()
                ),
            );
            // TODO: support several lib dirs
            if libraries.len() > 1 {
                bail!("Tests only support one library path containing a full 'ncf' library");
            }
            let run_log = cf_agent(technique_file, case_path.as_path(), libraries[0].as_path())?;
            let report_file = Path::new(TARGET_DIR).join(case_path.with_extension("json"));
            create_dir_all(report_file.parent().unwrap())?;
            fs::write(&report_file, serde_json::to_string_pretty(&run_log)?)?;
            ok_output(
                "Writing",
                format!(
                    "test report for {} into '{}'",
                    case_path.display(),
                    report_file.display()
                ),
            );
            // Run test checks
            case.check(test_dir)?;
        }
        Ok(())
    }

    /// Write output
    pub fn build(
        libraries: &[PathBuf],
        input: &Path,
        output_dir: &Path,
        standalone: bool,
        store_ids: bool,
    ) -> Result<()> {
        create_dir_all(output_dir).with_context(|| {
            format!("Failed to create target directory {}", output_dir.display())
        })?;
        let policy_str = read_to_string(input)
            .with_context(|| format!("Failed to read input from {}", input.display()))?;
        let methods = read_methods(libraries)?;
        create_dir_all(output_dir)?;

        // Read technique, only do it once
        let policy = read_technique(methods, &policy_str)?;

        if store_ids {
            let src_file = input.with_extension("ids.yml");
            let mut file = File::create(&src_file)
                .with_context(|| format!("Failed to create output file {}", src_file.display()))?;
            file.write_all(serde_yaml::to_string(&policy)?.as_bytes())?;
            ok_output("Wrote", src_file.display());
        }

        // Technique implementation
        for target in ALL_TARGETS {
            let policy_file =
                output_dir.join(Path::new(TECHNIQUE).with_extension(target.extension()));
            let mut file = File::create(&policy_file).with_context(|| {
                format!("Failed to create output file {}", policy_file.display())
            })?;
            file.write_all(compile(policy.clone(), *target, input, standalone)?.as_bytes())?;
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
        file.write_all(metadata(policy, input)?.as_bytes())?;
        ok_output("Wrote", metadata_file.display());

        // Resources folder
        let resources_path = input.parent().unwrap().join(RESOURCES_DIR);
        if resources_path.exists() {
            pub fn copy_recursively(
                source: impl AsRef<Path>,
                destination: impl AsRef<Path>,
            ) -> io::Result<()> {
                create_dir_all(&destination)?;
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
