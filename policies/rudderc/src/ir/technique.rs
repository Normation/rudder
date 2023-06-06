// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

use std::{collections::HashMap, fmt, str::FromStr};

use anyhow::{bail, Error};
use rudder_commons::ParameterType;
use serde::{de, Deserialize, Deserializer, Serialize};
use serde_yaml::Value;

use crate::{frontends::methods::method::MethodInfo, ir::condition::Condition};

/// Valid id for techniques, methods, etc.
///
/// Lowest common denominator between target platforms.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Hash)]
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

impl Default for Technique {
    fn default() -> Self {
        Self {
            format: 0,
            id: Id::from_str("my_technique").unwrap(),
            name: "My technique".to_string(),
            version: "1.0".to_string(),
            tags: None,
            category: None,
            description: Some("A technique".to_string()),
            documentation: None,
            items: vec![],
            parameters: vec![],
        }
    }
}

/// A Rudder technique (based on methods and/or modules)
#[derive(Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Technique {
    #[serde(default)]
    #[serde(skip_serializing_if = "Technique::format_is_default")]
    pub format: usize,
    pub id: Id,
    pub name: String,
    pub version: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tags: Option<Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub category: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub documentation: Option<String>,
    pub items: Vec<ItemKind>,
    #[serde(default)]
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub parameters: Vec<Parameter>,
}

impl Technique {
    fn format_is_default(format: &usize) -> bool {
        *format == 0
    }
}

#[derive(Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Parameter {
    pub id: Id,
    pub name: String,
    #[serde(default)]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    #[serde(default)]
    pub _type: ParameterType,
    // TODO: one day, merge with constraint on methods parameters
    #[serde(default = "Parameter::may_be_empty_default")]
    pub may_be_empty: bool,
}

impl Parameter {
    fn may_be_empty_default() -> bool {
        false
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(untagged)]
pub enum ItemKind {
    Block(Block),
    Module(Module),
    Method(Method),
}

// Same as untagged deserialization, but with improved error messages
impl<'de> Deserialize<'de> for ItemKind {
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where
        D: Deserializer<'de>,
    {
        let parsed = Value::deserialize(deserializer)?;
        let Some(map) = parsed.as_mapping() else {
            return Err(de::Error::custom("Modules should be a map"))
        };
        // Pre-guess the type to provide relevant error messages in case of incorrect fields
        match (map.get("items"), map.get("method"), map.get("module")) {
            (Some(_), _, _) => Ok(ItemKind::Block(
                Block::deserialize(parsed).map_err(de::Error::custom)?,
            )),
            (_, Some(_), _) => Ok(ItemKind::Method(
                Method::deserialize(parsed).map_err(de::Error::custom)?,
            )),
            (_, _, Some(_)) => Ok(ItemKind::Module(
                Module::deserialize(parsed).map_err(de::Error::custom)?,
            )),
            (None, None, None) => Err(de::Error::custom("Missing required parameters in module")),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Block {
    #[serde(default)]
    pub condition: Condition,
    pub name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tags: Option<Value>,
    pub items: Vec<ItemKind>,
    pub id: Id,
    #[serde(default)]
    pub reporting: BlockReporting,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Module {
    #[serde(default)]
    pub name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tags: Option<Value>,
    #[serde(default)]
    pub condition: Condition,
    pub params: HashMap<String, String>,
    pub module: String,
    pub id: Id,
    #[serde(default)]
    pub reporting: LeafReporting,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Method {
    #[serde(default)]
    pub name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tags: Option<Value>,
    #[serde(default)]
    pub condition: Condition,
    pub params: HashMap<String, String>,
    /// Method name like "package_present"
    pub method: String,
    pub id: Id,
    #[serde(default)]
    pub reporting: LeafReporting,
    #[serde(skip)]
    pub info: Option<&'static MethodInfo>,
}

#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
pub struct BlockReporting {
    #[serde(default)]
    pub mode: BlockReportingMode,
    #[serde(default)]
    pub id: Option<Id>,
}

impl fmt::Display for BlockReporting {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "{}",
            match self.mode {
                BlockReportingMode::Disabled => "disabled".to_string(),
                BlockReportingMode::Weighted => "weighted".to_string(),
                BlockReportingMode::WorstCaseWeightedOne => "worst-case-weighted-one".to_string(),
                BlockReportingMode::WorstCaseWeightedSum => "worst-case-weighted-sum".to_string(),
                BlockReportingMode::Focus => format!("focus:{}", self.id.as_ref().unwrap()),
            }
        )
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub enum BlockReportingMode {
    #[serde(rename = "worst-case-weighted-sum")]
    WorstCaseWeightedSum,
    #[serde(rename = "worst-case-weighted-one")]
    WorstCaseWeightedOne,
    #[serde(rename = "focus")]
    Focus,
    #[serde(rename = "weighted")]
    #[serde(alias = "enabled")]
    #[default]
    Weighted,
    #[serde(rename = "disabled")]
    Disabled,
}

impl fmt::Display for BlockReportingMode {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "{}",
            match self {
                Self::Disabled => "disabled",
                Self::Weighted => "weighted",
                Self::WorstCaseWeightedOne => "worst-case-weighted-one",
                Self::WorstCaseWeightedSum => "worst-case-weighted-sum",
                Self::Focus => "focus",
            }
        )
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct LeafReporting {
    #[serde(default)]
    pub mode: LeafReportingMode,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub enum LeafReportingMode {
    #[serde(rename = "enabled")]
    #[default]
    Enabled,
    #[serde(rename = "disabled")]
    Disabled,
}
