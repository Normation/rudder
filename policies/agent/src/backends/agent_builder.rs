// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS
use crate::FakeUnixAgent;
use crate::FakeWindowsAgent;
use crate::Verbosity;
use crate::{Agent, FakeAgentConfig};
use std::path::PathBuf;

/// Enum to hold either agent type
pub enum AgentType {
    Windows(FakeWindowsAgent),
    Unix(FakeUnixAgent),
}

impl AgentType {
    pub fn run(&self) -> Result<std::process::Output, anyhow::Error> {
        match self {
            AgentType::Windows(agent) => agent.run(),
            AgentType::Unix(agent) => agent.run(),
        }
    }

    pub fn config(&self) -> FakeAgentConfig {
        match self {
            AgentType::Windows(agent) => agent.config(),
            AgentType::Unix(agent) => agent.config(),
        }
    }

    pub fn directive_path(&self) -> PathBuf {
        match self {
            AgentType::Windows(agent) => agent.directive_path(),
            AgentType::Unix(agent) => agent.directive_path(),
        }
    }
    pub fn technique_path(&self) -> PathBuf {
        match self {
            AgentType::Windows(agent) => agent.technique_path(),
            AgentType::Unix(agent) => agent.technique_path(),
        }
    }
}

/// Builder for creating agents with common configuration
pub struct FakeAgentBuilder {
    workdir: PathBuf,
    verbosity: Verbosity,
    library_folder: PathBuf,
    technique_file_path: PathBuf,
    datastate_file_path: PathBuf,
}

impl FakeAgentBuilder {
    pub fn new(workdir: PathBuf) -> Self {
        Self {
            workdir: workdir.clone(),
            verbosity: Verbosity::Info,
            library_folder: PathBuf::new(),
            technique_file_path: PathBuf::new(),
            datastate_file_path: workdir.join("datastate.json"),
        }
    }

    pub fn verbosity(mut self, verbosity: Verbosity) -> Self {
        self.verbosity = verbosity;
        self
    }

    pub fn library_folder(mut self, library_folder: PathBuf) -> Self {
        self.library_folder = library_folder;
        self
    }

    pub fn technique_file_path(mut self, technique_file_path: PathBuf) -> Self {
        self.technique_file_path = technique_file_path;
        self
    }

    pub fn datastate_file_path(mut self, datastate_file_path: PathBuf) -> Self {
        self.datastate_file_path = datastate_file_path;
        self
    }

    pub fn build(self) -> AgentType {
        let config = FakeAgentConfig {
            work_dir: self.workdir.clone(),
            state_file_path: self.workdir.join("state.json"),
            verbosity: self.verbosity,
            library_folder: self.library_folder,
            report_file_path: self.workdir.join("reports.r"),
            datastate_file_path: self.datastate_file_path,
            log_file_path: self.workdir.join("debug.log"),
        };

        #[cfg(feature = "test-windows")]
        {
            AgentType::Windows(FakeWindowsAgent { config })
        }

        #[cfg(not(feature = "test-windows"))]
        {
            AgentType::Unix(FakeUnixAgent { config })
        }
    }
}
