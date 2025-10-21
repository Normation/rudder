// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use std::fmt::Display;
use std::fs;
use std::path::PathBuf;

#[derive(Serialize, Deserialize, Debug)]
pub enum AgentStatus {
    Disabled(Option<DateTime<Utc>>),
    Enabled,
}

impl Display for AgentStatus {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            AgentStatus::Disabled(d) => {
                if let Some(d) = d {
                    write!(f, "disabled since {}", d.to_rfc3339())
                } else {
                    write!(f, "disabled")
                }
            }
            AgentStatus::Enabled => write!(f, "enabled"),
        }
    }
}
impl AgentStatus {
    pub fn load_from_file(path: &PathBuf) -> AgentStatus {
        if !path.exists() {
            return AgentStatus::Enabled;
        }
        let modified = fs::metadata(path)
            .ok()
            .and_then(|m| m.modified().ok())
            .map(DateTime::<Utc>::from);
        AgentStatus::Disabled(modified)
    }

    pub fn save_to_file(&self, path: &PathBuf) -> Result<(), anyhow::Error> {
        match self {
            AgentStatus::Disabled(_) => {
                fs::File::create(path)?;
            }
            AgentStatus::Enabled => {
                fs::remove_file(path)?;
            }
        }
        Ok(())
    }
}
