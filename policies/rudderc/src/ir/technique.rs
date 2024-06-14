// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

use std::{
    collections::HashMap,
    fmt,
    fmt::{Display, Formatter},
    str::FromStr,
};

use anyhow::{bail, Context, Error, Result};
use rudder_commons::{methods::method::MethodInfo, PolicyMode, RegexConstraint, Select};
use serde::{de, Deserialize, Deserializer, Serialize};
use serde_yaml::Value;
use uuid::Uuid;

use crate::ir::condition::Condition;

pub const TECHNIQUE_FORMAT_VERSION: usize = 1;

/// Valid id for techniques.
///
/// Similar to `Id`, but does now allow dashes.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Hash)]
#[serde(transparent)]
pub struct TechniqueId {
    inner: String,
}

impl AsRef<String> for TechniqueId {
    fn as_ref(&self) -> &String {
        &self.inner
    }
}

impl From<TechniqueId> for String {
    fn from(id: TechniqueId) -> Self {
        id.inner
    }
}

impl FromStr for TechniqueId {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        fn valid_char(c: char) -> bool {
            c.is_ascii_alphanumeric() || c == '_'
        }

        if s.chars().all(valid_char) {
            Ok(TechniqueId {
                inner: s.to_string(),
            })
        } else {
            bail!("Invalid technique id: {}", s)
        }
    }
}

impl<'de> Deserialize<'de> for TechniqueId {
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where
        D: Deserializer<'de>,
    {
        let s = String::deserialize(deserializer)?;
        FromStr::from_str(&s).map_err(de::Error::custom)
    }
}

impl Display for TechniqueId {
    fn fmt(&self, f: &mut Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.inner)
    }
}

/// Valid id for blocks, methods, etc.
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
#[serde(deny_unknown_fields)]
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
            format: TECHNIQUE_FORMAT_VERSION,
            id: TechniqueId::from_str("my_technique").unwrap(),
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
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct Technique {
    #[serde(default = "Technique::default_format")]
    #[serde(skip_serializing_if = "Technique::format_is_default")]
    pub format: usize,
    pub id: TechniqueId,
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
        *format == TECHNIQUE_FORMAT_VERSION
    }

    fn default_format() -> usize {
        TECHNIQUE_FORMAT_VERSION
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
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
    #[serde(default)]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub default: Option<String>,
}

// Only used for parsing to allow proper error messages
// Represents the union of all fields of the ItemKinds.
#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct DeserItem {
    #[serde(default)]
    pub condition: Condition,
    #[serde(default)]
    pub name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub documentation: Option<String>,
    pub tags: Option<Value>,
    #[serde(default)]
    pub items: Vec<DeserItem>,
    #[serde(default)]
    pub id: Id,
    #[serde(default)]
    pub reporting: BlockReporting,
    #[serde(default)]
    pub params: HashMap<String, String>,
    pub method: Option<String>,
    pub module: Option<String>,
    #[serde(deserialize_with = "PolicyMode::from_string")]
    #[serde(default)]
    pub policy_mode_override: Option<PolicyMode>,
}

// Variant of Technique for first level of deserialization
#[derive(Clone, Debug, PartialEq, Eq, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct DeserTechnique {
    #[serde(default = "Technique::default_format")]
    pub format: usize,
    pub id: TechniqueId,
    pub name: String,
    pub version: String,
    pub tags: Option<Value>,
    pub category: Option<String>,
    pub description: Option<String>,
    pub documentation: Option<String>,
    #[serde(default)]
    pub items: Vec<DeserItem>,
    #[serde(default)]
    pub params: Vec<Parameter>,
}

impl DeserTechnique {
    pub fn to_technique(self) -> Result<Technique> {
        Ok(Technique {
            format: self.format,
            id: self.id,
            name: self.name,
            version: self.version,
            tags: self.tags,
            category: self.category,
            description: self.description,
            documentation: self.documentation,
            items: self
                .items
                .into_iter()
                .map(|i| i.into_kind())
                .collect::<Result<Vec<ItemKind>>>()?,
            params: self.params,
        })
    }
}

impl DeserItem {
    // Can't use TryFrom as the implementation is recursive
    fn into_kind(self) -> Result<ItemKind> {
        // discriminating fields
        match (
            self.method.is_some(),
            self.module.is_some(),
            !self.items.is_empty(),
            !self.params.is_empty(),
        ) {
            (true, false, false, true) => Ok(ItemKind::Method(Method {
                name: self.name.clone(),
                description: self.description.clone(),
                documentation: self.documentation.clone(),
                tags: self.tags,
                condition: self.condition,
                params: self.params,
                method: self.method.unwrap(),
                id: self.id.clone(),
                reporting: self.reporting.try_into().context(format!(
                    "Method {} ({}) has an unexpected reporting mode",
                    &self.name, &self.id
                ))?,
                info: None,
                policy_mode_override: self.policy_mode_override,
            })),
            (true, false, _, false) => {
                bail!("Method {} ({}) requires params", self.name, self.id)
            }
            (false, true, false, true) => Ok(ItemKind::Module(Module {
                name: self.name.clone(),
                description: self.description.clone(),
                documentation: self.documentation.clone(),
                tags: self.tags,
                condition: self.condition,
                params: self.params,
                module: self.module.unwrap(),
                id: self.id.clone(),
                reporting: self.reporting.try_into().context(format!(
                    "Module {} ({}) has an unexpected reporting mode",
                    self.name, self.id
                ))?,
                policy_mode_override: self.policy_mode_override,
            })),
            (false, true, _, false) => {
                bail!("Module {} ({}) requires params", self.name, self.id)
            }
            (false, false, true, false) => Ok(ItemKind::Block(Block {
                name: if self.name.is_empty() {
                    bail!("Block {} requires a 'name' parameter", self.id)
                } else {
                    self.name
                },
                description: self.description.clone(),
                documentation: self.documentation.clone(),
                tags: self.tags,
                condition: self.condition,
                id: self.id,
                reporting: self.reporting,
                items: self
                    .items
                    .into_iter()
                    .map(|i| i.into_kind().unwrap())
                    .collect(),
                policy_mode_override: self.policy_mode_override,
            })),
            (false, false, false, false) => {
                bail!("Block {} ({}) requires items", self.name, self.id)
            }
            (true, true, _, _) => bail!(
                "Item {} ({}) cannot be both a method and a resource",
                self.name,
                self.id
            ),
            (_, _, true, true) => bail!(
                "Item {} ({}) cannot have both items and params",
                self.name,
                self.id
            ),
            (false, false, false, true) => bail!(
                "Item {} ({}) required either a method or module parameter",
                self.name,
                self.id
            ),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(untagged)]
pub enum ItemKind {
    Block(Block),
    Module(Module),
    Method(Method),
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Block {
    #[serde(default)]
    #[serde(skip_serializing_if = "Condition::is_defined")]
    pub condition: Condition,
    pub name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub documentation: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tags: Option<Value>,
    pub items: Vec<ItemKind>,
    #[serde(default)]
    pub id: Id,
    #[serde(default)]
    pub reporting: BlockReporting,
    #[serde(default)]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub policy_mode_override: Option<PolicyMode>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Module {
    #[serde(default)]
    pub name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub documentation: Option<String>,
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
    #[serde(default)]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub policy_mode_override: Option<PolicyMode>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Method {
    #[serde(default)]
    pub name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub documentation: Option<String>,
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
    #[serde(default)]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub policy_mode_override: Option<PolicyMode>,
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

// only used for deserialization from large type
impl TryFrom<BlockReporting> for LeafReporting {
    type Error = Error;

    fn try_from(value: BlockReporting) -> Result<Self, Self::Error> {
        match (value.mode, value.id) {
            (BlockReportingMode::Disabled, None) => Ok(LeafReporting {
                mode: LeafReportingMode::Disabled,
            }),
            (BlockReportingMode::Weighted, None) => Ok(LeafReporting {
                mode: LeafReportingMode::Enabled,
            }),
            _ => bail!("Unsupported reporting mode in a method or module"),
        }
    }
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
    use std::str::FromStr;

    use pretty_assertions::assert_eq;

    use crate::ir::technique::{Id, Parameter};

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
