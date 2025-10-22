// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

use std::{
    env::set_current_dir,
    fs::{self, create_dir_all},
    path::{Path, PathBuf},
};

use anyhow::bail;
use anyhow::{Context, Result, anyhow};
use rudder_cli::custom_panic_hook_ignore_sigpipe;
use tracing::debug;

use crate::cli::{Command, MainArgs};

pub mod backends;
pub mod cli;
pub mod compiler;
mod doc;
pub mod frontends;
pub mod ir;
pub mod test;

pub const TARGET_DIR: &str = "target";
pub const TESTS_DIR: &str = "tests";
/// Name of the technique files. The extension indicates the format.
pub const TECHNIQUE: &str = "technique";
pub const YAML_EXTENSIONS: &[&str] = &["yml", "yaml"];
pub const METADATA_FILE: &str = "metadata.xml";
pub const RESOURCES_DIR: &str = "resources";

/// Where to read the library if no path were provided
pub const DEFAULT_LIB_PATH: &str = "/var/rudder/ncf/";

#[cfg(windows)]
pub const DEFAULT_AGENT_PATH: &str = "C:\\Program Files\\Rudder";
#[cfg(unix)]
pub const DEFAULT_AGENT_PATH: &str = "/opt/rudder/bin/";

fn find_input() -> Result<PathBuf> {
    let maybe_input = YAML_EXTENSIONS
        .iter()
        .map(|e| Path::new(TECHNIQUE).with_extension(e))
        .find(|p| p.exists());
    match maybe_input {
        Some(input) => Ok(input.to_path_buf()),
        None => bail!(
            "No input '{}.{}' file provided",
            TECHNIQUE,
            YAML_EXTENSIONS[0]
        ),
    }
}

/// Main entry point for rudderc
///
/// # Error management
///
/// The current process is to stop at first error, and move it up to `main()` where it will
/// be displayed.
pub fn run(args: MainArgs) -> Result<()> {
    custom_panic_hook_ignore_sigpipe();

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
        // Support being passed the technique.yml file directly
        let actual_cwd = if cwd.ends_with(TECHNIQUE) {
            let parent = cwd
                .parent()
                .ok_or_else(|| anyhow!("Could not open {} technique directory", cwd.display()))?;
            if !parent.exists() {
                fs::create_dir_all(parent).context(format!(
                    "Creating the parent directory {}",
                    parent.display()
                ))?;
            }
            parent
        } else {
            if !cwd.exists() {
                fs::create_dir_all(&cwd)
                    .context(format!("Creating the directory {}", cwd.display()))?;
            }
            cwd.as_path()
        };
        set_current_dir(actual_cwd).with_context(|| {
            format!(
                "Failed to change the current workdir to '{}'",
                cwd.display()
            )
        })?;
    }
    debug!("Working directory: '{}'", cwd.canonicalize()?.display());

    match args.command {
        Command::Init => action::init(&cwd, None),
        Command::New { name } => {
            create_dir_all(&name)
                .with_context(|| format!("Failed to create technique directory {name}"))?;
            action::init(&cwd.join(&name), Some(name))
        }
        Command::Clean => action::clean(target.as_path()),
        Command::Check { library } => {
            let library = check_libraries(library)?;
            let input = find_input()?;
            action::check(library.as_slice(), input.as_path())
        }
        Command::Build {
            library,
            output,
            standalone,
            export,
            store_ids,
        } => {
            let library = check_libraries(library)?;
            let input = find_input()?;
            let actual_output = output.unwrap_or(target);
            if actual_output.exists()
                && actual_output.canonicalize()? == input.canonicalize()?.parent().unwrap()
            {
                bail!("Output directory cannot be the same as the input directory");
            }

            action::build(
                library.as_slice(),
                input.as_path(),
                actual_output.as_path(),
                standalone,
                store_ids || export,
            )?;
            if export {
                action::export(&cwd, actual_output)?;
            }
            Ok(())
        }
        Command::Test {
            library,
            agent,
            filter,
            agent_verbose,
        } => {
            let library = check_libraries(library)?;
            let input = find_input()?;
            let input = input.as_path();
            action::build(library.as_slice(), input, target.as_path(), true, false)?;
            action::test(
                input,
                &target,
                Path::new(TESTS_DIR),
                library.as_slice(),
                agent,
                filter,
                agent_verbose,
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
        fs::{self, File, create_dir, create_dir_all, read_to_string, remove_dir_all},
        io::{self, Read, Write},
        path::{Path, PathBuf},
        process::Command,
    };

    use anyhow::{Context, Result, bail};
    use rudder_commons::{ALL_TARGETS, Target, logs::ok_output};
    use walkdir::WalkDir;
    use zip::write::{ExtendedFileOptions, FileOptions, ZipWriter};

    pub use crate::compiler::compile;
    use crate::{
        METADATA_FILE, RESOURCES_DIR, TECHNIQUE, TESTS_DIR, YAML_EXTENSIONS,
        backends::{unix::cfengine::cf_agent, windows::test::win_agent},
        compiler::{metadata, read_technique},
        doc::{Format, book},
        frontends::read_methods,
        ir::Technique,
        test::TestCase,
    };

    /// Create a technique skeleton
    pub fn init(output: &Path, name: Option<String>) -> Result<()> {
        let mut technique = Technique::default();
        if let Some(n) = name {
            technique.name = n;
        }
        let t = serde_yaml::to_string(&technique)?;
        let tech_path = output.join(TECHNIQUE);
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
        if !stdout {
            ok_output("Read", format!("{} methods", methods.len()));
        }

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
        if let Some(f) = file_to_open
            && open
        {
            ok_output("Opening", f.display());
            let _ = Command::new("xdg-open").args([f]).output();
        }

        Ok(())
    }

    /// Linter mode, check JSON schema compliance and ability to compile
    pub fn check(libraries: &[PathBuf], input: &Path) -> Result<()> {
        // Compilation test
        let methods = read_methods(libraries)?;
        ok_output("Read", format!("{} methods", methods.len()));

        let policy_str = read_to_string(input)
            .with_context(|| format!("Failed to read input from {}", input.display()))?;
        let policy = read_technique(methods, &policy_str, true)?;
        for target in ALL_TARGETS {
            compile(policy.clone(), *target, input, false)?;
        }
        //
        ok_output("Checked", input.display());
        Ok(())
    }

    /// Run a test
    pub fn test(
        technique_src: &Path,
        target_dir: &Path,
        test_dir: &Path,
        libraries: &[PathBuf],
        agent: String,
        filter: Option<String>,
        agent_verbose: bool,
    ) -> Result<()> {
        let agent_path = PathBuf::from(agent);
        // Run everything relatively to the test directory
        // Collect test cases
        let mut cases = vec![];
        if test_dir.exists() {
            for entry in fs::read_dir(test_dir)? {
                let e = entry?;
                let path = e.path();
                let extension = path
                    .extension()
                    .unwrap_or(Default::default())
                    .to_str()
                    .unwrap_or("");
                if e.file_type()?.is_file() && YAML_EXTENSIONS.contains(&extension) {
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
            let case_id = case_path
                .file_stem()
                .unwrap()
                .to_string_lossy()
                .into_owned();
            ok_output("Testing", case_path.to_string_lossy());
            let yaml = read_to_string(&case_path)?;
            let case: TestCase = serde_yaml::from_str(&yaml)?;
            // Run test setup
            case.setup(test_dir, target_dir)?;
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
            if libraries.is_empty() {
                bail!("One library path must be passed using the '--library' option");
            }
            let run_log = match case.target {
                Target::Unix => {
                    cf_agent(
                        &target_dir.join("technique.cf"),
                        case_path.as_path(),
                        libraries[0].as_path(),
                        &agent_path,
                        agent_verbose,
                    )?
                    .runlog
                }
                Target::Windows => {
                    // Read the technique
                    // TODO: reuse parsed technique from build step
                    let methods = read_methods(libraries)?;
                    ok_output("Read", format!("{} methods", methods.len()));
                    let policy_str = read_to_string(technique_src).with_context(|| {
                        format!("Failed to read input from {}", technique_src.display())
                    })?;
                    let policy = read_technique(methods, &policy_str, true)?;

                    win_agent(
                        target_dir,
                        &policy,
                        &case,
                        &case_id,
                        &agent_path,
                        agent_verbose,
                    )?
                }
            };
            let report_file = target_dir.join(Path::new(&case_id).with_extension("json"));
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
            let res = case.check(test_dir, &report_file, target_dir);
            // Run anyway
            case.cleanup(test_dir, target_dir)?;
            res?;
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
        ok_output("Read", format!("{} methods", methods.len()));

        create_dir_all(output_dir)?;

        // Read technique, only do it once
        let policy = read_technique(methods, &policy_str, true)?;

        if store_ids {
            let policy_without_resolving_loops = read_technique(methods, &policy_str, false)?;
            let src_file = input.with_extension("ids.yml");
            let mut file = File::create(&src_file)
                .with_context(|| format!("Failed to create output file {}", src_file.display()))?;
            file.write_all(serde_yaml::to_string(&policy_without_resolving_loops)?.as_bytes())?;
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

    pub fn export(src: &Path, dir: PathBuf) -> Result<()> {
        // We don't need to parse everything, let's just extract what we need
        // We use the technique with ids
        let technique_src = src.join(TECHNIQUE).with_extension("ids.yml");
        let yml: serde_yaml::Value =
            serde_yaml::from_str(&read_to_string(&technique_src).context(format!(
                "Could not read source technique {}",
                technique_src.display()
            ))?)?;
        let id = yml.get("id").unwrap().as_str().unwrap();
        let version = yml.get("version").unwrap().as_str().unwrap();
        let category = yml
            .get("category")
            .map(|c| c.as_str().unwrap())
            .unwrap_or("ncf_techniques");
        create_dir_all(&dir).context(format!("Creating output directory {}", dir.display()))?;
        let actual_output = dir.join(format!("{id}-{version}.zip"));

        let file = File::create(&actual_output).context(format!(
            "Creating export output file {}",
            &actual_output.display()
        ))?;
        let options: FileOptions<ExtendedFileOptions> = FileOptions::default();
        let mut zip = ZipWriter::new(file);

        let zip_dir = format!("archive/techniques/{category}/{id}/{version}");

        // Technique
        zip.start_file(format!("{zip_dir}/{TECHNIQUE}"), options.clone())?;
        let mut buffer = Vec::new();
        let mut f = File::open(&technique_src).context(format!(
            "Opening technique source {}",
            technique_src.display()
        ))?;
        f.read_to_end(&mut buffer)?;
        zip.write_all(&buffer)?;

        // Resources
        let resources_dir = src.join("resources");
        if resources_dir.exists() {
            for r in WalkDir::new(&resources_dir).into_iter() {
                // Only select files
                let entry = r?;
                if !entry.file_type().is_file() {
                    continue;
                }
                let p = entry.path();
                zip.start_file(
                    format!(
                        "{}/resources/{}",
                        zip_dir,
                        p.strip_prefix(&resources_dir).unwrap().display()
                    ),
                    options.clone(),
                )?;
                let mut buffer = Vec::new();
                let mut f = File::open(p)?;
                f.read_to_end(&mut buffer)?;
                zip.write_all(&buffer)?;
            }
        }
        zip.finish()?;
        ok_output("Writing", actual_output.display());
        Ok(())
    }
}
