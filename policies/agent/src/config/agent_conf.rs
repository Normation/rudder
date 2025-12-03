// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS
use serde::{Deserialize, Serialize};
use std::path::PathBuf;

#[derive(Deserialize, Serialize, Debug)]
pub struct AgentConf {
    pub cert_validation: bool,
    #[serde(default = "default_https_port")]
    pub https_port: u16,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub https_proxy: Option<String>,
}
fn default_https_port() -> u16 {
    443
}

impl Default for AgentConf {
    fn default() -> Self {
        AgentConf {
            cert_validation: false,
            https_port: default_https_port(),
            https_proxy: None,
        }
    }
}
impl AgentConf {
    pub fn load_from_str(s: &str) -> Result<AgentConf, anyhow::Error> {
        Ok(toml::from_str::<Self>(s)?)
    }
    pub fn load_from_file(path: &PathBuf) -> Result<Self, anyhow::Error> {
        let content = std::fs::read_to_string(path)?;
        Ok(toml::from_str::<Self>(&content)?)
    }
    pub fn save_to_file(&self, path: &PathBuf) -> Result<(), anyhow::Error> {
        let content = toml::to_string(&self)?;
        std::fs::write(path, content)?;
        Ok(())
    }
}
