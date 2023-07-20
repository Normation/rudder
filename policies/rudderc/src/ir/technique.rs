// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

use std::fmt::{Display, Formatter};
use std::{collections::HashMap, fmt, str::FromStr};

use anyhow::{bail, Error, Result};
use rudder_commons::{RegexConstraint, Select};
use serde::{de, Deserialize, Deserializer, Serialize};
use serde_yaml::Value;
use uuid::Uuid;

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

impl Display for Id {
    fn fmt(&self, f: &mut Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.inner)
    }
}

impl Default for Id {
    fn default() -> Self {
        Self {
            inner: Uuid::new_v4().to_string(),
        }
    }
}

// We choose to skip the following types (for now):
//
// * raw: not used currently, might have unwanted effects
// * masterPassword: a very specific use case, hard to understand and document
// * datetime: not used currently

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize, Default)]
#[serde(rename_all = "kebab-case")]
/// Type, i.e., what will be passed
///
/// We try to separate the nature of the data from the way it will be represented in the form.
pub enum ParameterType {
    Boolean,
    // String-like
    String,
    #[default]
    MultilineString,
    // For now, nothing magic
    Json,
    Yaml,
    Mail,
    Ip,
    Ipv4,
    Ipv6,
    // Numbers
    Integer,
    SizeB,
    SizeKb,
    SizeMb,
    SizeGb,
    SizeTb,
    // Special
    Permissions,
    SharedFile,
    Password,
}

impl Display for ParameterType {
    fn fmt(&self, f: &mut Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "{}",
            match self {
                ParameterType::Boolean => "boolean",
                ParameterType::String => "string",
                ParameterType::MultilineString => "multiline-string",
                ParameterType::Json => "json",
                ParameterType::Yaml => "yaml",
                ParameterType::Mail => "mail",
                ParameterType::Ip => "ip",
                ParameterType::Ipv4 => "ipv4",
                ParameterType::Ipv6 => "ipv6",
                ParameterType::Integer => "integer",
                ParameterType::SizeB => "size-b",
                ParameterType::SizeKb => "size-kb",
                ParameterType::SizeMb => "size-mb",
                ParameterType::SizeGb => "size-gb",
                ParameterType::SizeTb => "size-tb",
                ParameterType::Permissions => "permissions",
                ParameterType::SharedFile => "shared-file",
                ParameterType::Password => "password",
            }
        )
    }
}

#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub struct Constraints {
    #[serde(default = "Constraints::default_allow_empty")]
    pub allow_empty: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[serde(default)]
    pub regex: Option<RegexConstraint>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[serde(default)]
    pub select: Option<Vec<Select>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    #[serde(default)]
    pub password_hashes: Option<Vec<PasswordType>>,
}

impl Constraints {
    fn default_allow_empty() -> bool {
        false
    }
}

impl PasswordType {
    /// Acceptable hashes for passwords
    ///
    /// Prevents giving users direct access to unsafe hashes.
    pub fn acceptable() -> Vec<Self> {
        vec![
            PasswordType::PreHashed,
            // Even the sha2-crypt hashes are not that good
            // https://pthree.org/2018/05/23/do-not-use-sha256crypt-sha512crypt-theyre-dangerous/
            // https://fedoraproject.org/wiki/Changes/yescrypt_as_default_hashing_method_for_shadow
            PasswordType::Sha256Crypt,
            PasswordType::Sha512Crypt,
            PasswordType::Sha256CryptAix,
            PasswordType::Sha512CryptAix,
        ]
    }
}

#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum PasswordType {
    PreHashed,
    Plain,
    Md5,
    Sha1,
    Sha256,
    Sha512,
    Md5Crypt,
    Sha256Crypt,
    Sha512Crypt,
    // AIX uses specific variants of those crypt hashes
    Md5CryptAix,
    Sha256CryptAix,
    Sha512CryptAix,
    UnixCryptDes,
}

impl Display for PasswordType {
    fn fmt(&self, f: &mut Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "{}",
            match self {
                PasswordType::PreHashed => "pre-hashed",
                PasswordType::Plain => "plain",
                PasswordType::Md5 => "md5",
                PasswordType::Sha1 => "sha1",
                PasswordType::Sha256 => "sha256",
                PasswordType::Sha512 => "sha512",
                PasswordType::Md5Crypt => "linux-shadow-md5",
                PasswordType::Sha256Crypt => "linux-shadow-sha256",
                PasswordType::Sha512Crypt => "linux-shadow-sha512",
                PasswordType::Md5CryptAix => "aix-smd5",
                PasswordType::Sha256CryptAix => "aix-ssha256",
                PasswordType::Sha512CryptAix => "aix-ssha512",
                PasswordType::UnixCryptDes => "unix-crypt-des",
            }
        )
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
            params: vec![],
        }
    }
}

/// A Rudder technique (based on methods and/or modules)
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
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
    pub params: Vec<Parameter>,
}

impl Technique {
    fn format_is_default(format: &usize) -> bool {
        *format == 0
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Parameter {
    #[serde(default)]
    pub id: Id,
    pub name: String,
    #[serde(default)]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    #[serde(default)]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub documentation: Option<String>,
    #[serde(default)]
    #[serde(rename = "type")]
    pub _type: ParameterType,
    #[serde(default)]
    pub constraints: Constraints,
    //#[serde(default)]
    //pub escaping: Escaping,
    #[serde(default)]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub default: Option<String>,
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
            return Err(de::Error::custom("Modules should be a map"));
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
    #[serde(skip_serializing_if = "Condition::is_defined")]
    pub condition: Condition,
    pub name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tags: Option<Value>,
    pub items: Vec<ItemKind>,
    #[serde(default)]
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
    #[serde(skip_serializing_if = "Condition::is_defined")]
    pub condition: Condition,
    pub params: HashMap<String, String>,
    pub module: String,
    #[serde(default)]
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
    #[serde(skip_serializing_if = "Condition::is_defined")]
    pub condition: Condition,
    pub params: HashMap<String, String>,
    /// Method name like "package_present"
    pub method: String,
    #[serde(default)]
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
    #[serde(skip_serializing_if = "Option::is_none")]
    pub id: Option<Id>,
}

impl Display for BlockReporting {
    fn fmt(&self, f: &mut Formatter<'_>) -> fmt::Result {
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
#[serde(rename_all = "kebab-case")]
pub enum BlockReportingMode {
    WorstCaseWeightedSum,
    WorstCaseWeightedOne,
    Focus,
    #[serde(alias = "enabled")]
    #[default]
    Weighted,
    Disabled,
}

impl Display for BlockReportingMode {
    fn fmt(&self, f: &mut Formatter<'_>) -> fmt::Result {
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
#[serde(rename_all = "kebab-case")]
pub enum LeafReportingMode {
    #[default]
    Enabled,
    Disabled,
}

#[cfg(test)]
mod tests {
    use crate::ir::technique::{Id, Parameter};
    use pretty_assertions::assert_eq;
    use std::str::FromStr;

    #[test]
    fn it_parses_parameters() {
        let src = r#"{ "id": "3439bbb0-d8f1-4c43-95a9-0c56bfb8c27a", "name": "server_a" }"#;
        let p: Parameter = serde_json::from_str(src).unwrap();
        let reference = Parameter {
            id: Id::from_str("3439bbb0-d8f1-4c43-95a9-0c56bfb8c27a").unwrap(),
            name: "server_a".to_string(),
            description: None,
            documentation: None,
            _type: Default::default(),
            constraints: Default::default(),
            default: None,
        };
        assert_eq!(p, reference);
    }
}
