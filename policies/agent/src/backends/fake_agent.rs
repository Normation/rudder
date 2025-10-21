use crate::{AgentConf, AgentState, AgentStatus, BuildInfo, PolicyParams, Verbosity};
use anyhow::Context;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::Output;
use tracing::debug;

#[derive(Debug, Clone)]
pub struct FakeAgentConfig {
    pub work_dir: PathBuf,
    pub state_file_path: PathBuf,
    pub verbosity: Verbosity,
    pub library_folder: PathBuf,
    pub report_file_path: PathBuf,
    pub datastate_file_path: PathBuf,
    pub log_file_path: PathBuf,
}

pub trait Agent {
    /// Render the agent-specific standalone code
    fn render(&self) -> Result<String, anyhow::Error>;

    /// Get the filename for the standalone script
    fn standalone_path(&self) -> PathBuf;
    fn directive_path(&self) -> PathBuf;
    fn technique_path(&self) -> PathBuf;

    /// Execute the agent with the standalone script
    fn execute(&self, script_path: &Path) -> Result<Output, anyhow::Error>;

    /// Get the agent configuration
    fn config(&self) -> FakeAgentConfig;
}

/// Common runner for the fake agents
pub struct AgentTestRunner;

impl AgentTestRunner {
    pub fn write_state_file(config: &FakeAgentConfig) -> Result<(), anyhow::Error> {
        let agent_state = AgentState {
            root: config.work_dir.to_path_buf(),
            policy_params: PolicyParams::default(),
            agent_conf: AgentConf::default(),
            uuid: Some("fake-uuid".to_string()),
            policy_server: None,
            build_info: BuildInfo::default(),
            status: AgentStatus::Enabled,
        };

        debug!(
            "Writing agent state to {}",
            &config.state_file_path.display()
        );
        fs::write(
            &config.state_file_path,
            serde_json::to_string_pretty(&agent_state).context("Serializing the agent state")?,
        )
        .context(format!(
            "Writing the state file to {}",
            &config.state_file_path.display()
        ))?;

        Ok(())
    }

    pub fn run<A: Agent>(agent: &A) -> Result<Output, anyhow::Error> {
        let config = &agent.config();

        // Write state file (common)
        Self::write_state_file(config)?;

        // Render and write standalone script
        let standalone_path = agent.standalone_path();
        debug!("Writing standalone file to {}", &standalone_path.display());

        fs::write(
            &standalone_path,
            agent
                .render()
                .context("Failed to compute the standalone content")?,
        )
        .context("Writing the standalone file")?;

        // Execute agent-specific logic
        agent.execute(&standalone_path)
    }
}
