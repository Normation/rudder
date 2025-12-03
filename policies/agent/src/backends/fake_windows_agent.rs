// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS
use crate::{Agent, AgentTestRunner, FakeAgentConfig};
use anyhow::Context;
use askama::Template;
use std::path::{Path, PathBuf};
use std::process::{Command, Output};

#[cfg(unix)]
pub const POWERSHELL_BIN: &str = "pwsh";
pub const POWERSHELL_OPTS: &[&str] = &["-NoProfile", "-NonInteractive"];
#[cfg(windows)]
pub const POWERSHELL_BIN: &str = "PowerShell.exe";

#[derive(Template)]
#[template(escape = "none", path = "fake_windows_agent.ps1.askama")]
struct WindowsAgentTemplate {
    pub library_folder: String,
    pub log_file_path: String,
    pub verbosity: String,
    pub state_file_path: String,
    pub technique_file_path: String,
    pub report_file_path: String,
    pub datastate_file_path: String,
    pub directive_file_path: String,
}

#[derive(Debug)]
pub struct FakeWindowsAgent {
    pub config: FakeAgentConfig,
}

impl FakeWindowsAgent {
    pub fn new(config: FakeAgentConfig) -> Self {
        Self { config }
    }

    pub fn run(&self) -> Result<Output, anyhow::Error> {
        AgentTestRunner::run(self)
    }
}

impl Agent for FakeWindowsAgent {
    fn render(&self) -> Result<String, anyhow::Error> {
        let t = WindowsAgentTemplate {
            state_file_path: self.config.state_file_path.display().to_string(),
            verbosity: self.config.verbosity.to_string(),
            library_folder: self.config.library_folder.display().to_string(),
            technique_file_path: self.technique_path().display().to_string(),
            report_file_path: self.config.report_file_path.display().to_string(),
            datastate_file_path: self.config.datastate_file_path.display().to_string(),
            directive_file_path: self.directive_path().display().to_string(),
            log_file_path: self.config.log_file_path.display().to_string(),
        };
        let s = t.render().context("Failed to render template")?;
        Ok(s)
    }

    fn standalone_path(&self) -> PathBuf {
        self.config().work_dir.join("standalone.ps1")
    }

    fn directive_path(&self) -> PathBuf {
        self.config().work_dir.join("directive.ps1")
    }

    fn technique_path(&self) -> PathBuf {
        self.config().work_dir.join("technique.ps1")
    }

    fn execute(&self, script_path: &Path) -> Result<Output, anyhow::Error> {
        Command::new(POWERSHELL_BIN)
            .args(POWERSHELL_OPTS)
            .arg("-Command")
            .arg(format!(
                "&'{}'",
                script_path
                    .canonicalize()
                    .context("Resolving the standalone path")?
                    .to_string_lossy()
            ))
            .current_dir(&self.config.work_dir)
            .output()
            .context("Reading execution output")
    }

    fn config(&self) -> FakeAgentConfig {
        self.config.clone()
    }
}
