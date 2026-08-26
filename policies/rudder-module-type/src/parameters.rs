// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Normation SAS

//! Rudder module protocol encapsulated in CFEngine custom promise type

use serde::{
    Deserialize, Deserializer, Serialize,
    de::{self, Visitor},
};
use serde_json::{Map, Value};
use std::{fmt, path::PathBuf};

use crate::cfengine::protocol::ActionPolicy;

/// Deserializes a `usize` from either a number or a string containing one.
///
/// CFEngine passes promise attributes as strings, while the values we get from
/// other callers are plain JSON numbers, so both have to be accepted.
fn deserialize_usize_from_string<'de, D: Deserializer<'de>>(d: D) -> Result<usize, D::Error> {
    struct UsizeFromString;

    impl<'de> Visitor<'de> for UsizeFromString {
        type Value = usize;

        fn expecting(&self, f: &mut fmt::Formatter) -> fmt::Result {
            f.write_str("a non-negative integer, or a string containing one")
        }

        fn visit_u64<E: de::Error>(self, v: u64) -> Result<Self::Value, E> {
            usize::try_from(v).map_err(de::Error::custom)
        }

        fn visit_i64<E: de::Error>(self, v: i64) -> Result<Self::Value, E> {
            usize::try_from(v).map_err(de::Error::custom)
        }

        fn visit_str<E: de::Error>(self, v: &str) -> Result<Self::Value, E> {
            v.parse().map_err(de::Error::custom)
        }
    }

    d.deserialize_any(UsizeFromString)
}

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
    #[serde(deserialize_with = "deserialize_usize_from_string")]
    pub agent_frequency_minutes: usize,
    /// Version of the Rudder module protocol
    #[serde(default)]
    #[serde(deserialize_with = "deserialize_usize_from_string")]
    pub(crate) rudder_module_protocol: usize,
    /// Opaque ID for reports matching
    #[serde(default)]
    pub report_id: Option<String>,
    /// Module type parameters
    pub data: Map<String, Value>,
    // Only passed if warn
    #[serde(default)]
    pub(crate) action_policy: ActionPolicy,
}

impl Parameters {
    pub fn new(node_id: String, data: Map<String, Value>, state_dir: PathBuf) -> Self {
        Self {
            data,
            node_id,
            state_dir,
            agent_frequency_minutes: Self::default_agent_frequency_minutes(),
            temporary_dir: Self::default_temporary_dir(),
            backup_dir: Self::default_backup_dir(),
            ..Default::default()
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

#[cfg(test)]
mod tests {
    use super::*;

    #[derive(Deserialize)]
    struct Test {
        #[serde(deserialize_with = "deserialize_usize_from_string")]
        value: usize,
    }

    #[test]
    fn it_parses_numbers_from_numbers_and_strings() {
        assert_eq!(
            serde_json::from_str::<Test>(r#"{"value": 30}"#)
                .unwrap()
                .value,
            30
        );
        assert_eq!(
            serde_json::from_str::<Test>(r#"{"value": "30"}"#)
                .unwrap()
                .value,
            30
        );
        assert_eq!(
            serde_json::from_str::<Test>(r#"{"value": "0"}"#)
                .unwrap()
                .value,
            0
        );
    }

    #[test]
    fn it_rejects_invalid_numbers() {
        for v in [r#""""#, r#""a""#, r#""-1""#, "-1", "1.5", "true", "null"] {
            let json = format!(r#"{{"value": {v}}}"#);
            assert!(
                serde_json::from_str::<Test>(&json).is_err(),
                "{v} should be rejected"
            );
        }
    }
}
