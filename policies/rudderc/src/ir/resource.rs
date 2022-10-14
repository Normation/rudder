// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

use anyhow::{bail, Error};
use std::collections::HashMap;
use std::str::FromStr;

use serde::{de, Deserialize, Deserializer, Serialize};
use serde_yaml::Value;

/// Valid id for techniques, methods, etc.
///
/// Lowest common denominator between target platforms.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(transparent)]
pub struct Id {
    inner: String,
}

impl AsRef<String> for Id {
    fn as_ref(&self) -> &String {
        &self.inner
    }
}

impl From<Id> for String {
    fn from(id: Id) -> Self {
        id.inner
    }
}

impl FromStr for Id {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        fn valid_char(c: char) -> bool {
            c.is_ascii_alphanumeric() || c == '-' || c == '_'
        }

        if s.chars().all(valid_char) {
            Ok(Id {
                inner: s.to_string(),
            })
        } else {
            bail!("Invalid id: {}", s)
        }
    }
}

impl<'de> Deserialize<'de> for Id {
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where
        D: Deserializer<'de>,
    {
        let s = String::deserialize(deserializer)?;
        FromStr::from_str(&s).map_err(de::Error::custom)
    }
}

#[derive(Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Policy {
    #[serde(default)]
    pub format: usize,
    pub id: Id,
    pub name: String,
    pub version: String,
    pub description: Option<String>,
    pub documentation: Option<String>,
    pub resources: Vec<Resource>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(untagged)]
pub enum Resource {
    BlockResource(BlockResource),
    LeafResource(LeafResource),
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct BlockResource {
    pub condition: String,
    pub name: String,
    pub params: HashMap<String, String>,
    #[serde(rename = "type")]
    pub resource_type: String,
    // contains either states or resources
    pub resources: Vec<Resource>,
    pub id: Id,
    pub reporting: Option<ReportingPolicy>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct LeafResource {
    pub name: String,
    pub params: HashMap<String, String>,
    #[serde(rename = "type")]
    pub resource_type: String,
    // contains either states or resources
    pub states: Vec<State>,
    pub id: Id,
    pub reporting: Option<ReportingPolicy>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct State {
    // TODO specific type with custom deserializer that check validity
    // class regex or variables
    pub condition: String,
    pub id: Id,
    pub meta: Value,
    pub name: String,
    pub params: HashMap<String, String>,
    // comes from stdlib
    pub report_parameter: String,
    #[serde(rename = "type")]
    pub state_type: String,
    pub reporting: Option<ReportingPolicy>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ReportingPolicy {
    pub enabled: bool,
    #[serde(default)]
    pub compute: ReportingCompute,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum ReportingCompute {
    #[serde(rename = "worst-case-weighted-sum")]
    WorstCaseWeightedSum,
    #[serde(rename = "worst-case-weighted-one")]
    WorstCaseWeightedOne,
    #[serde(rename = "focus")]
    Focus(String),
    #[serde(rename = "weighted")]
    Weighted,
}

impl Default for ReportingPolicy {
    fn default() -> Self {
        ReportingPolicy {
            enabled: true,
            compute: ReportingCompute::default(),
        }
    }
}

impl Default for ReportingCompute {
    fn default() -> Self {
        ReportingCompute::Weighted
    }
}
