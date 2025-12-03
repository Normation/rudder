// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BuildInfo {
    pub git_ref: String,
    pub main_version: String,
    pub ncf_commit: String,
    pub nightly_tag: String,
    pub release_step: String,
    pub repository_version: String,
    #[serde(rename = "rudder-agent_commit")]
    pub rudder_agent_commit: String,
    #[serde(rename = "rudder-api-client_commit")]
    pub rudder_api_client_commit: String,
    pub rudder_commit: String,
    #[serde(rename = "rudder-doc_commit")]
    pub rudder_doc_commit: String,
    pub rudder_msi_version: String,
    #[serde(rename = "rudder-packages_commit")]
    pub rudder_packages_commit: String,
    #[serde(rename = "rudder-techniques_commit")]
    pub rudder_techniques_commit: String,
    pub rudder_version: String,
    pub version: String,
}
impl Default for BuildInfo {
    fn default() -> Self {
        BuildInfo {
            git_ref: "".to_string(),
            main_version: "9.0".to_string(),
            ncf_commit: "".to_string(),
            nightly_tag: "".to_string(),
            release_step: "".to_string(),
            repository_version: "".to_string(),
            rudder_agent_commit: "".to_string(),
            rudder_api_client_commit: "".to_string(),
            rudder_commit: "".to_string(),
            rudder_doc_commit: "".to_string(),
            rudder_msi_version: "".to_string(),
            rudder_packages_commit: "".to_string(),
            rudder_techniques_commit: "".to_string(),
            rudder_version: "9.0.0.0".to_string(),
            version: "9.0.0".to_string(),
        }
    }
}
impl BuildInfo {
    pub fn load_from_json(json: &str) -> Result<Self, serde_json::Error> {
        serde_json::from_str(json)
    }

    pub fn load_from_file(path: &std::path::Path) -> Result<Self, Box<dyn std::error::Error>> {
        let content = std::fs::read_to_string(path)?;
        Ok(serde_json::from_str(&content)?)
    }
}
