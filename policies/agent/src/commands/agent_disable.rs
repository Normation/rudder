// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS
use crate::{AgentState, AgentStatus, consts};
use chrono::Utc;

pub fn run_agent_disable(agent_state: AgentState) -> Result<(), anyhow::Error> {
    let path = agent_state.root.join(consts::DISABLE_AGENT_PATH);
    let s = AgentStatus::Disabled(Option::from(Utc::now()));
    s.save_to_file(&path)
}
