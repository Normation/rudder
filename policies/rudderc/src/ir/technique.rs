// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

use anyhow::{Context, Error, Result, bail};
use rudder_commons::{PolicyMode, RegexConstraint, Select, methods::method::MethodInfo};
use serde::{Deserialize, Deserializer, Serialize, de};
use serde_yaml::Value;
use std::{
    collections::HashMap,
    fmt::{self, Display, Formatter},
    str::FromStr,
};
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
                PasswordType::UnixCryptDes => "unix-crypt-des",
            }
        )
    }
}
#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
pub enum ForeachResolvedState {
    Main,
    Virtual,
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
            policy_types: vec![],
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
    #[serde(default)]
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub policy_types: Vec<String>,
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
    #[serde(skip_serializing_if = "Option::is_none")]
    pub foreach_name: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub foreach: Option<Vec<HashMap<String, String>>>,
    // If a DeserItem is generated by a loop and it not its first branch, equals true
    #[serde(default)]
    pub foreach_resolved_state: Option<ForeachResolvedState>,
}

impl Default for DeserItem {
    // Mostly used for tests
    fn default() -> Self {
        Self {
            condition: Default::default(),
            name: "My item".to_string(),
            description: None,
            documentation: None,
            tags: None,
            items: vec![],
            id: Default::default(),
            reporting: Default::default(),
            params: Default::default(),
            method: None,
            module: None,
            policy_mode_override: None,
            foreach_name: None,
            foreach: None,
            foreach_resolved_state: None,
        }
    }
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
    pub policy_types: Vec<String>,
    #[serde(default)]
    pub items: Vec<DeserItem>,
    #[serde(default)]
    pub params: Vec<Parameter>,
}

impl DeserTechnique {
    pub fn to_technique(self, resolve_loop: bool) -> Result<Technique> {
        let preparsed_technique = if resolve_loop {
            let unflatten_loop_resolved_items: Result<Vec<Vec<DeserItem>>> = self
                .items
                .into_iter()
                .map(|i| i.resolve_loop(vec![], None))
                .collect();
            let binding =
                unflatten_loop_resolved_items.with_context(|| "Failed to resolve loops items")?;
            binding.into_iter().flatten().collect::<Vec<DeserItem>>()
        } else {
            self.items
        };
        let items: Result<Vec<ItemKind>> = preparsed_technique
            .into_iter()
            .map(|i| i.clone().into_kind())
            .collect();
        Ok(Technique {
            format: self.format,
            id: self.id,
            name: self.name,
            version: self.version,
            tags: self.tags,
            category: self.category,
            description: self.description,
            documentation: self.documentation,
            policy_types: self.policy_types,
            items: items?,
            params: self.params,
        })
    }
}

#[derive(Clone, Debug)]
pub struct ForeachContext {
    variable_name: String,
    data: HashMap<String, String>,
    index: usize,
}

impl ForeachContext {
    fn new(variable_name: Option<String>, data: HashMap<String, String>, index: usize) -> Self {
        let v = if let Some(vn) = variable_name {
            vn
        } else {
            "item".to_string()
        };
        ForeachContext {
            variable_name: v,
            data,
            index,
        }
    }
    fn expand(self, template: String) -> String {
        let pattern = format!(r"\$\{{{}\.(\w+)\}}", regex::escape(&self.variable_name));
        let regex = regex::Regex::new(&pattern)
            .unwrap_or_else(|_| panic!("Invalid loop variable iterator {}", &self.variable_name));
        regex
            .replace_all(&template, |captures: &regex::Captures| {
                let key = &captures[1];
                // Replace with the value from the hashmap, or keep the placeholder if the key doesn't exist.
                match self.data.get(key) {
                    Some(value) => value.clone().to_owned(),
                    None => captures[0].to_string(),
                }
            })
            .to_string()
    }
}

impl DeserItem {
    fn replace_using_context(&self, context: Vec<ForeachContext>) -> Result<DeserItem> {
        let name = context
            .iter()
            .fold(self.name.clone(), |acc, x| x.clone().expand(acc));
        let condition =
            Condition::from_str(
                &context.clone()
                    .into_iter()
                    .fold(self.condition.to_string(), |acc, x| x.clone().expand(acc.to_string()))
            ).with_context(|| format!("Failed to render the condition in item {} while resolving the foreach items, using context {:#?}", &self.id, context))?;
        let documentation = self.documentation.as_ref().map(|d| {
            context
                .iter()
                .fold(d.to_owned(), |acc, x| x.clone().expand(acc))
        });
        let description = self.description.as_ref().map(|d| {
            context
                .iter()
                .fold(d.to_owned(), |acc, x| x.clone().expand(acc))
        });
        let id = if context.is_empty() {
            self.id.clone()
        } else {
            Id::from_str(&format!(
                "{}-{}",
                self.id,
                context
                    .iter()
                    .map(|i| i.index.to_string())
                    .collect::<Vec<String>>()
                    .join("-")
            ))?
        };
        let params = self
            .params
            .clone()
            .into_iter()
            .map(|p| {
                let value: String = context
                    .iter()
                    .fold(p.1.to_string(), |acc, x| x.clone().expand(acc));
                (p.0, value)
            })
            .collect::<HashMap<String, String>>();
        Ok(DeserItem {
            condition,
            name,
            description,
            documentation,
            params,
            id,
            ..self.clone()
        })
    }
    fn resolve_loop(
        self,
        parent_context: Vec<ForeachContext>,
        parent_foreach_state: Option<ForeachResolvedState>,
    ) -> Result<Vec<DeserItem>> {
        // Replace every description, documentation, name, condition, params fields in the tree, using the given context.

        // If force_virtual is set we are already on a fork
        // If not, tag the first iteration as the new master

        // Resolve the state of the first sub item as it is the only one difficult to identify
        let first_item_resolved_state = match parent_foreach_state {
            None => {
                if self.foreach.is_some() {
                    Some(ForeachResolvedState::Main)
                } else {
                    None
                }
            }
            Some(_) => parent_foreach_state.clone(),
        };
        let mut is_first_item = true;
        let branches: Result<Vec<DeserItem>> = if let Some(ref cases) = self.foreach {
            cases
                .iter()
                .enumerate()
                .map(|b| {
                    let mut branch_context = parent_context.clone();
                    branch_context.push(ForeachContext::new(
                        self.foreach_name.clone(),
                        b.1.to_owned(),
                        b.0,
                    ));
                    let branch_resolved_state = if is_first_item {
                        first_item_resolved_state.clone()
                    } else {
                        Some(ForeachResolvedState::Virtual)
                    };
                    let children = self
                        .items
                        .iter()
                        .map(|child| {
                            child
                                .clone()
                                .resolve_loop(branch_context.clone(), branch_resolved_state.clone())
                                .with_context(|| {
                                    format!(
                                        "Failed to resolve item {} while resolving foreach fields",
                                        child.id
                                    )
                                })
                        })
                        .collect::<Result<Vec<Vec<DeserItem>>>>();
                    let flatten_children = children?.into_iter().flatten().collect();
                    let item = DeserItem {
                        foreach_resolved_state: branch_resolved_state,
                        foreach_name: None,
                        foreach: None,
                        items: flatten_children,
                        ..self.replace_using_context(branch_context)?
                    };
                    is_first_item = false;
                    Ok(item)
                })
                .collect()
        } else {
            let raw_children: Result<Vec<Vec<DeserItem>>> = self
                .items
                .iter()
                .map(|child| {
                    child
                        .clone()
                        .resolve_loop(parent_context.clone(), first_item_resolved_state.clone())
                })
                .collect();
            let children = raw_children?.into_iter().flatten().collect();
            Ok(vec![DeserItem {
                foreach_resolved_state: first_item_resolved_state,
                items: children,
                ..self.replace_using_context(parent_context.clone())?
            }])
        };
        branches
    }

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
                resolved_foreach_state: self.foreach_resolved_state,
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
                resolved_foreach_state: self.foreach_resolved_state,
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
                resolved_foreach_state: self.foreach_resolved_state,
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
    #[serde(default = "default_bool::<true>")]
    #[serde(skip_serializing)]
    pub resolved_foreach_state: Option<ForeachResolvedState>,
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
    #[serde(default = "default_bool::<true>")]
    #[serde(skip_serializing)]
    pub resolved_foreach_state: Option<ForeachResolvedState>,
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
    #[serde(default = "default_bool::<true>")]
    #[serde(skip_serializing)]
    pub resolved_foreach_state: Option<ForeachResolvedState>,
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
                BlockReportingMode::FocusWorst => "focus-worst".to_string(),
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
    FocusWorst,
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
                Self::FocusWorst => "focus-worst",
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
    use std::collections::HashMap;
    use std::str::FromStr;

    use pretty_assertions::assert_eq;

    use crate::ir::technique::{DeserItem, ForeachContext, ForeachResolvedState, Id, Parameter};

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

    // DeserItem Tests
    #[test]
    fn it_should_render_loop_variables_using_context_1() {
        let d = DeserItem {
            name: "My ${item.key1} item is ${plouf.key1}".to_string(),
            id: Id::from_str("10437ad9-db29-47c7-a44b-88e48020e65f").unwrap(),
            ..DeserItem::default()
        };
        let context = vec![
            ForeachContext::new(
                None,
                HashMap::from([("key1".to_string(), "templatized".to_string())]),
                0,
            ),
            ForeachContext::new(
                Some("plouf".to_string()),
                HashMap::from([("key1".to_string(), "this one".to_string())]),
                1,
            ),
        ];
        assert_eq!(
            DeserItem {
                name: "My templatized item is this one".to_string(),
                id: Id::from_str("10437ad9-db29-47c7-a44b-88e48020e65f-0-1").unwrap(),
                ..d.clone()
            },
            d.replace_using_context(context).unwrap()
        )
    }
    #[test]
    fn it_should_not_render_nested_templates_as_the_replacement_is_done_node_by_node_without_recursion()
     {
        let child = DeserItem {
            name: "The nested item is not ${item.key1}".to_string(),
            ..DeserItem::default()
        };
        let parent = DeserItem {
            name: "My ${item.key1} item is ${plouf.key1}".to_string(),
            documentation: Some("${item.${plouf.key2}} ${plouf.${plouf.key2}}".to_string()),
            items: vec![child],
            id: Id::from_str("7c4ca751-a757-4103-88a0-e7ca0ea91e54").unwrap(),
            ..DeserItem::default()
        };
        let context = vec![
            ForeachContext::new(
                None,
                HashMap::from([("key1".to_string(), "templatized".to_string())]),
                0,
            ),
            ForeachContext::new(
                Some("plouf".to_string()),
                HashMap::from([
                    ("key1".to_string(), "this one".to_string()),
                    ("key2".to_string(), "key1".to_string()),
                ]),
                1,
            ),
        ];
        assert_eq!(
            DeserItem {
                name: "My templatized item is this one".to_string(),
                documentation: Some("${item.key1} ${plouf.key1}".to_string()),
                id: Id::from_str("7c4ca751-a757-4103-88a0-e7ca0ea91e54-0-1").unwrap(),
                ..parent.clone()
            },
            parent.replace_using_context(context).unwrap()
        )
    }

    #[test]
    fn foreach_should_be_well_rendered() {
        let simple_method = DeserItem {
            id: Id::from_str("584f924e-a19b-448b-9c7e-9aafae7063c1").unwrap(),
            method: Some("package_present".to_string()),
            params: HashMap::from([
                ("path".to_string(), "${item.root}/file.txt".to_string()),
                ("lines".to_string(), "${item.lines}".to_string()),
                ("enforce".to_string(), "true".to_string()),
            ]),
            foreach: Some(vec![
                HashMap::from([
                    ("root".to_string(), "/1".to_string()),
                    ("lines".to_string(), "foo".to_string()),
                ]),
                HashMap::from([
                    ("root".to_string(), "/2".to_string()),
                    ("lines".to_string(), "bar".to_string()),
                ]),
            ]),
            ..DeserItem::default()
        };
        let expected = vec![
            DeserItem {
                id: Id::from_str("584f924e-a19b-448b-9c7e-9aafae7063c1-0").unwrap(),
                method: Some("package_present".to_string()),
                params: HashMap::from([
                    ("path".to_string(), "/1/file.txt".to_string()),
                    ("lines".to_string(), "foo".to_string()),
                    ("enforce".to_string(), "true".to_string()),
                ]),
                foreach_resolved_state: Some(ForeachResolvedState::Main),
                ..DeserItem::default()
            },
            DeserItem {
                id: Id::from_str("584f924e-a19b-448b-9c7e-9aafae7063c1-1").unwrap(),
                method: Some("package_present".to_string()),
                params: HashMap::from([
                    ("path".to_string(), "/2/file.txt".to_string()),
                    ("lines".to_string(), "bar".to_string()),
                    ("enforce".to_string(), "true".to_string()),
                ]),
                foreach_resolved_state: Some(ForeachResolvedState::Virtual),
                ..DeserItem::default()
            },
        ];
        assert_eq!(expected, simple_method.resolve_loop(vec![], None).unwrap());
    }
}
