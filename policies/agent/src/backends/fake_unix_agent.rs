use crate::{Agent, AgentTestRunner, FakeAgentConfig};
use anyhow::Context;
use askama::Template;
use std::path::{Path, PathBuf};
use std::process::{Command, Output};
use std::{env, fs};
use tracing::debug;

const CF_BIN_FOLDER: &str = "/opt/rudder/bin";

#[derive(Template)]
#[template(escape = "none", path = "fake_unix_agent.cf.askama")]
struct UnixAgentTemplate {
    pub work_dir: String,
    pub library_folder: String,
    pub technique_file_path: String,
    pub directive_file_path: String,
    pub datastate_file_path: String,
}

#[derive(Debug)]
pub struct FakeUnixAgent {
    pub config: FakeAgentConfig,
}

impl FakeUnixAgent {
    pub fn new(config: FakeAgentConfig) -> Self {
        Self { config }
    }

    pub fn run(&self) -> Result<Output, anyhow::Error> {
        AgentTestRunner::run(self)
    }

    /// Setup CFEngine-specific workspace and binaries
    fn setup_cfengine_workspace(&self) -> Result<PathBuf, anyhow::Error> {
        // source
        let cf_bin_path = if let Ok(env_value) = env::var("CF_BIN_FOLDER") {
            env_value
        } else {
            CF_BIN_FOLDER.to_string()
        };
        // destination
        let cf_workdir = self.config.work_dir.join("workspace");
        let cf_bin_dir = cf_workdir.join("bin");

        debug!(
            "Creating workspace directory {}",
            &self.config.work_dir.display()
        );
        fs::create_dir(&cf_workdir).context(format!(
            "Creating the cfengine workspace dir in {}",
            cf_workdir.display()
        ))?;

        fs::create_dir(&cf_bin_dir).context(format!(
            "Creating the cfengine binary dir in {}",
            cf_bin_dir.display()
        ))?;

        debug!(
            "Copy cfengine binaries from {} to {}",
            cf_bin_path,
            cf_bin_dir.display()
        );

        for binary in ["cf-promises", "cf-agent"] {
            let source = PathBuf::from(&cf_bin_path).join(binary);
            let destination = PathBuf::from(&cf_bin_dir).join(binary);
            fs::copy(&source, &destination).context(format!(
                "An error occurred while copying cf-promises to the workdir '{}' from '{}'",
                destination.to_string_lossy(),
                source.to_string_lossy(),
            ))?;
        }
        Ok(cf_workdir)
    }
}

impl Agent for FakeUnixAgent {
    fn render(&self) -> Result<String, anyhow::Error> {
        let t = UnixAgentTemplate {
            work_dir: self.config.work_dir.display().to_string(),
            library_folder: self.config.library_folder.display().to_string(),
            datastate_file_path: self.config.datastate_file_path.display().to_string(),
            technique_file_path: self.technique_path().display().to_string(),
            directive_file_path: self.directive_path().display().to_string(),
        };
        let s = t.render().context("Failed to render template")?;
        Ok(s)
    }

    fn standalone_path(&self) -> PathBuf {
        self.config().work_dir.join("standalone.cf")
    }

    fn directive_path(&self) -> PathBuf {
        self.config().work_dir.join("directive.cf")
    }

    fn technique_path(&self) -> PathBuf {
        self.config().work_dir.join("technique.cf")
    }

    fn execute(&self, script_path: &Path) -> Result<Output, anyhow::Error> {
        // Setup CFEngine workspace
        let cf_workdir = self.setup_cfengine_workspace()?;
        let cf_bin_dir = cf_workdir.join("bin");
        Command::new(cf_bin_dir.join("cf-agent"))
            .arg(self.config.verbosity.to_flag())
            .arg("--no-lock")
            .arg("--workdir")
            .arg(cf_workdir.display().to_string())
            .arg("--file")
            .arg(script_path.display().to_string())
            .output()
            .context("Reading execution output")
    }

    fn config(&self) -> FakeAgentConfig {
        self.config.clone()
    }
}
