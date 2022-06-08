// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Normation SAS

//! Rudder resource protocol encapsulated in CFEngine custom promise type

use std::path::PathBuf;

use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};

use crate::resource_type::PolicyMode;

#[derive(Debug, PartialEq, Serialize, Deserialize, Clone)]
pub struct Parameters {
    /// Where to store temporary files for the promise
    #[serde(default = "Parameters::default_temporary_dir")]
    pub temporary_dir: PathBuf,
    /// Unique node identifier
    pub node_id: Option<String>,
    /// Policy mode
    ///
    /// Default is enforce.
    #[serde(default)]
    pub policy_mode: PolicyMode,
    /// Version of the Rudder resource protocol
    pub rudder_resource_protocol: String,
    /// Resource type parameters
    pub data: Map<String, Value>,
}

impl Parameters {
    fn default_temporary_dir() -> PathBuf {
        #[cfg(target_family = "unix")]
        let r = PathBuf::from("/var/rudder/tmp/");
        #[cfg(target_family = "windows")]
        let r = PathBuf::from(r"C:\Program Files\Rudder\tmp");
        r
    }
}
