use crate::{AgentState, AgentStatus, consts};
use chrono::Utc;

pub fn run_agent_disable(agent_state: AgentState) -> Result<(), anyhow::Error> {
    let path = agent_state.root.join(consts::DISABLE_AGENT_PATH);
    let s = AgentStatus::Disabled(Option::from(Utc::now()));
    s.save_to_file(&path)
}
