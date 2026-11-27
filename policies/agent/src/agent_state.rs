// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS
use crate::config::{AgentConf, AgentStatus, BuildInfo, PolicyParams};
use crate::paths::Paths;
use serde::{Deserialize, Serialize};
use std::marker::PhantomData;
use std::path::PathBuf;
use tracing::debug;
use anyhow::{Result, bail, Context};
use std::fmt;
use std::str::FromStr;

/// Generic type to read/write from a simple Text file
/// Sub type must implement TryFrom<&str> and Display
pub struct TextFile<T> {
    path: PathBuf,
    _t: PhantomData<T>
}
impl<T> TextFile<T>
    where
    T: FromStr + fmt::Display,
    T::Err: Into<anyhow::Error>,
{

    pub fn new(path: &PathBuf) -> Self {
        Self {
            path: path.clone(),
            _t: PhantomData
        }
    }

    pub fn load(&self) -> Result<Option<T>> {
        if !self.path.exists() {
            Ok(None)
        } else {
            let raw = std::fs::read_to_string(&self.path)
                .with_context(||format!("Could not read TextFile '{}'", self.path.display()))?;
            let content = raw.trim();
            Ok(Some(T::from_str(content).map_err(Into::into)?))
        }
    }

    pub fn save(&self, value: &str) -> Result<()> {
        std::fs::write(&self.path, value.to_string()).with_context(|| format!("Could not write data '{}' to the TextFile '{}'", value, self.path.display()))?;
        Ok(())
    }
}

pub struct NodeId(String);

impl fmt::Display for NodeId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl FromStr for NodeId {
    type Err = anyhow::Error;
    fn from_str(s: &str) -> std::prelude::v1::Result<Self, Self::Err> {
        let is_uuid = s.len() == 36
            && s.chars().enumerate().all(|(i, c)| match i {
                8 | 13 | 18 | 23 => c == '-',
                _ => c.is_ascii_hexdigit(),
            });
        if s == "root" || is_uuid {
            Ok(Self(s.to_owned()))
        } else {
            bail!("The node id format is incorrect. It should either be 'root' or a valid UUID. Found '{s}'")
        }
    }
}


/// Common reader to load values from the filesystem.
/// This only reads/sets values. It does not hold any data.
#[derive(Debug)]
pub struct AgentState {
    pub paths: Paths,
}

impl AgentState {
    pub fn new(paths: Paths) -> Self { Self { paths }}
    pub fn unix() -> Self { Self::new(Paths::unix()) }
    pub fn windows() -> Self { Self::new(Paths::windows()) }

    pub fn node_id(&self) -> TextFile<NodeId> {
        TextFile::new(&self.paths.node_id)
    }

    //pub fn test(
    //    root: PathBuf,
    //    policy_params: PolicyParams,
    //    agent_conf: AgentConf,
    //    build_info: BuildInfo,
    //) -> Result<AgentState, anyhow::Error> {
    //    let uuid = read_trimmed_string(constants::UUID_PATH);
    //    let policy_server = read_trimmed_string(constants::POLICY_SERVER_CONF_PATH);
    //    let status =
    //        AgentStatus::load_from_file(&PathBuf::from(&root).join(constants::DISABLE_AGENT_PATH));
    //    let state = AgentState {
    //        root,
    //        policy_params,
    //        agent_conf,
    //        build_info,
    //        uuid,
    //        policy_server,
    //        status,
    //    };
    //    Ok(state)
    //}
}

/// A serializable snapshot of the agent state
#[derive(Serialize, Deserialize, Debug)]
pub struct AgentStateSnapshot {
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

fn read_trimmed_string(path: &str) -> Option<String> {
    match std::fs::read_to_string(path) {
        Ok(content) => Some(content.trim().to_string()),
        Err(e) => {
            debug!("{}", e);
            None
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rstest::rstest;
    use tempfile::TempDir;

    #[test]
    fn missing_file_is_none() {
        let file = TextFile::<NodeId>::new(&PathBuf::from("/path/that/does/not/exist"));
        assert!(file.load().unwrap().is_none())
    }
}
