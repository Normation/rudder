// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

use std::{collections::HashMap, fmt, path::PathBuf, str::FromStr};

use anyhow::{bail, Error};
use serde::{de, Deserialize, Deserializer, Serialize};
use serde_yaml::Value;

use crate::{frontends::methods::method::MethodInfo, ir::condition::Condition};

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

impl fmt::Display for Id {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.inner)
    }
}

/// A Rudder technique (based on methods and/or resources)
#[derive(Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Technique {
    #[serde(default)]
    pub format: usize,
    pub id: Id,
    pub name: String,
    pub version: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub category: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub documentation: Option<String>,
    pub resources: Vec<ResourceKind>,
    #[serde(default)]
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub parameters: Vec<Parameter>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    #[serde(default)]
    pub files: Vec<PathBuf>,
}

#[derive(Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Parameter {
    id: Id,
    name: String,
    description: Option<String>,
    // FIXME represent as constraint?
    may_be_empty: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Block {
    #[serde(default)]
    pub condition: Condition,
    pub name: String,
    pub resources: Vec<ResourceKind>,
    pub id: Id,
    #[serde(default)]
    pub reporting: BlockReporting,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(untagged)]
pub enum ResourceKind {
    Block(Block),
    Resource(Resource),
    Method(Method),
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Resource {
    #[serde(default)]
    pub name: String,
    pub meta: Value,
    #[serde(default)]
    pub condition: Condition,
    pub params: HashMap<String, String>,
    pub resource: String,
    pub id: Id,
    #[serde(default)]
    pub reporting: LeafReporting,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Method {
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub condition: Condition,
    pub params: HashMap<String, String>,
    pub method: String,
    pub id: Id,
    #[serde(default)]
    pub reporting: LeafReporting,
    #[serde(skip)]
    pub info: Option<&'static MethodInfo>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum BlockReporting {
    #[serde(rename = "worst-case-weighted-sum")]
    WorstCaseWeightedSum,
    #[serde(rename = "worst-case-weighted-one")]
    WorstCaseWeightedOne,
    #[serde(rename = "focus")]
    Focus(String),
    #[serde(rename = "weighted")]
    #[serde(alias = "enabled")]
    Weighted,
    #[serde(rename = "disabled")]
    Disabled,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum LeafReporting {
    #[serde(rename = "enabled")]
    Enabled,
    #[serde(rename = "disabled")]
    Disabled,
}

impl Default for BlockReporting {
    fn default() -> Self {
        BlockReporting::Weighted
    }
}

impl Default for LeafReporting {
    fn default() -> Self {
        LeafReporting::Enabled
    }
}
