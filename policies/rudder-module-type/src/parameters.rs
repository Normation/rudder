// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Normation SAS

//! Rudder module protocol encapsulated in CFEngine custom promise type

use std::path::PathBuf;
use chrono::Duration;
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};

use crate::cfengine::protocol::ActionPolicy;

#[derive(Debug, PartialEq, Eq, Serialize, Deserialize, Clone, Default)]
pub struct Parameters {
    /// Where to store temporary files
    #[serde(default = "Parameters::default_temporary_dir")]
    pub temporary_dir: PathBuf,
    /// Where to store file backups
    #[serde(default = "Parameters::default_backup_dir")]
    pub backup_dir: PathBuf,
    /// Where to store persistent state files
    #[serde(default = "Parameters::default_state_dir")]
    pub state_dir: PathBuf,
    /// Unique node identifier
    pub node_id: String,
    /// Agent run frequency in minutes
    #[serde(default = "Parameters::default_agent_frequency_minutes")]
    pub agent_frequency_minutes: usize,
    /// Version of the Rudder module protocol
    #[serde(default)]
    pub(crate) rudder_module_protocol: usize,
    /// Module type parameters
    pub data: Map<String, Value>,
    // Only passed if warn
    #[serde(default)]
    pub(crate) action_policy: ActionPolicy,
}

impl Parameters {
    pub fn new(node_id: String, data: Map<String, Value>, state_dir: PathBuf) -> Self {
        Self {
            data, node_id, state_dir, agent_frequency_minutes: Self::default_agent_frequency_minutes(), ..Default::default()
        }
    }

    fn default_temporary_dir() -> PathBuf {
        #[cfg(target_family = "unix")]
        let r = PathBuf::from("/var/rudder/tmp/");
        #[cfg(target_family = "windows")]
        let r = PathBuf::from(r"C:\Program Files\Rudder\tmp\");
        r
    }

    fn default_backup_dir() -> PathBuf {
        #[cfg(target_family = "unix")]
        let r = PathBuf::from("/var/rudder/modified-files/");
        #[cfg(target_family = "windows")]
        let r = PathBuf::from(r"C:\Program Files\Rudder\modified-files\");
        r
    }

    fn default_state_dir() -> PathBuf {
        #[cfg(target_family = "unix")]
        let r = PathBuf::from("/var/rudder/cfengine-community/state/");
        #[cfg(target_family = "windows")]
        let r = PathBuf::from(r"C:\Program Files\Rudder\state\");
        r
    }

    fn default_agent_frequency_minutes() -> usize {
        5
    }
}
