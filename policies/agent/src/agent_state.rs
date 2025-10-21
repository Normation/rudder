use crate::config::{AgentConf, AgentStatus, BuildInfo, PolicyParams};
use crate::consts;
use serde::{Deserialize, Serialize};
use std::path::PathBuf;
use tracing::debug;

#[derive(Serialize, Deserialize, Debug)]
pub struct AgentState {
    #[serde(rename = "rudderFolder")]
    pub root: PathBuf,
    #[serde(rename = "systemParams")]
    pub policy_params: PolicyParams,
    #[serde(rename = "agentConf")]
    pub agent_conf: AgentConf,
    #[serde(rename = "nodeId")]
    pub uuid: Option<String>,
    pub policy_server: Option<String>,
    pub build_info: BuildInfo,
    pub status: AgentStatus,
}
impl AgentState {
    pub fn new(
        root: PathBuf,
        policy_params: PolicyParams,
        agent_conf: AgentConf,
        build_info: BuildInfo,
    ) -> Result<AgentState, anyhow::Error> {
        let uuid = read_trimmed_string(consts::UUID_PATH);
        let policy_server = read_trimmed_string(consts::POLICY_SERVER_CONF_PATH);
        let status =
            AgentStatus::load_from_file(&PathBuf::from(&root).join(consts::DISABLE_AGENT_PATH));
        let state = AgentState {
            root,
            policy_params,
            agent_conf,
            build_info,
            uuid,
            policy_server,
            status,
        };
        Ok(state)
    }
}
fn read_trimmed_string(path: &str) -> Option<String> {
    match std::fs::read_to_string(path) {
        Ok(content) => Some(content.trim().to_string()),
        Err(e) => {
            debug!("{}", e);
            None
        }
    }
}
