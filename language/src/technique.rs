// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

mod cfstrings;
mod from_ir;

use crate::{
    error::*,
    ir::{ir2::IR2, resource::StateDeclaration, value::Value},
    language_lib::{LanguageLib, LibMethod},
};
use colored::Colorize;
use lazy_static::lazy_static;
use regex::{Captures, Regex};
use serde::de::{MapAccess, Visitor};
use serde::ser::SerializeStruct;
use serde::{
    de::{self, Deserializer},
    Deserialize, Serialize, Serializer,
};
use std::collections::HashMap;
use std::fmt::{Display, Formatter};
use std::{
    convert::TryFrom,
    fmt,
    str::{self, FromStr},
};
use toml::Value as TomlValue;
use uuid::Uuid;

// Techniques are limited subsets of CFEngine in JSON representation
// that only carry method calls and Rudder metadata

// might change later
pub type TechniqueFmt = String;

// required Version type de/serializer
fn version_into_string<S>(v: &Version, s: S) -> std::result::Result<S::Ok, S::Error>
where
    S: Serializer,
{
    s.serialize_str(&v.to_string())
}
fn string_into_version<'de, D>(deserializer: D) -> std::result::Result<Version, D::Error>
where
    D: Deserializer<'de>,
{
    let str_version = String::deserialize(deserializer)?;
    Version::from_str(&str_version).map_err(|e| de::Error::custom(e))
}

#[derive(Serialize, Deserialize, Copy, Clone)]
#[cfg_attr(test, derive(PartialEq, Debug))]
struct Version(u8, u8);
impl fmt::Display for Version {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(f, "{}.{}", self.0, self.1)
    }
}
impl FromStr for Version {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self> {
        // expected format is "X.X", X being positive numbers
        let values = s
            .split(".")
            .map(|value| {
                value.parse::<u8>().map_err(|e| {
                    Error::new(format!(
                        "'{}' is not a valid version (composed of two positive integers): {}",
                        s, e
                    ))
                })
            })
            .collect::<Result<Vec<u8>>>()?;
        if values.len() != 2 {
            return Err(Error::new(format!(
                "version ('{}') is not composed of 2 integers",
                s
            )));
        }
        Ok(Version(values[0], values[1]))
    }
}

#[derive(Serialize, Deserialize)]
pub struct Technique {
    bundle_name: String,
    #[serde(
        serialize_with = "version_into_string",
        deserialize_with = "string_into_version"
    )] // >=6.1
    version: Version,
    #[serde(default = "default_category")] // >=6.2
    category: String,
    description: String,
    name: String,
    method_calls: Vec<MethodElem>,
    #[serde(rename = "parameter")]
    interpolated_parameters: Vec<InterpolatedParameter>,
    // does not appear in labs. maybe skip only if empty?
    #[serde(default)] // >=6.2
    resources: Vec<Resource>,
}
fn default_category() -> String {
    "ncf_techniques".to_owned()
}
impl Technique {
    /// creates a Technique that will be used to generate a string representation of a rudderlang or json technique
    pub(super) fn from_json(input: &str, content: &str) -> Result<Self> {
        info!("|- {} {}", "Parsing".bright_green(), input.bright_yellow());

        serde_json::from_str::<Self>(content)
            .map_err(|e| Error::new(format!("Technique from JSON: {}", e)))
    }

    pub(super) fn to_json(&self) -> Result<TechniqueFmt> {
        info!(
            "|- {} (translation phase)",
            "Generating JSON code".bright_green()
        );

        serde_json::to_string_pretty(self)
            .map_err(|e| Error::new(format!("Technique to JSON: {}", e)))
    }

    pub(super) fn to_rudderlang(&self, lib: &LanguageLib) -> Result<String> {
        info!(
            "|- {} (translation phase)",
            "Generating rudderlang code".bright_green()
        );

        let (parameters_meta, parameter_list): (Vec<String>, Vec<String>) = self
            .interpolated_parameters
            .iter()
            .map(|p| p.to_rudderlang())
            .collect::<Result<Vec<(String, String)>>>()?
            .into_iter()
            .unzip();
        let parameters_meta_fmt = match parameters_meta.is_empty() {
            true => "".to_owned(),
            false => format!("\n@  {}\n@", parameters_meta.join(",\n@  ")),
        };

        let calls = self
            .method_calls
            .iter()
            .map(|c| match c {
                MethodElem::MethodCall(callData) => callData.to_rudderlang(&Vec::new(), lib),
                MethodElem::MethodBlock(blockData) => blockData.to_rudderlang(&Vec::new(), lib),
            })
            .collect::<Result<Vec<String>>>()?;
        let calls_fmt = match calls.is_empty() {
            true => "".to_owned(),
            false => format!("\n  {}\n", calls.join("\n\n  ")),
        };

        Ok(format!(
            r#"# Generated from json technique
@format = 0
@name = "{name}"
@description = "{description}"
@version = "{version}"
@category = "{category}"
@parameters = [{parameters_meta}]

resource technique_{bundle_name}({parameter_list})

technique_{bundle_name} state technique() {{{calls}}}
"#,
            name = self.name,
            description = self.description,
            version = self.version,
            category = self.category,
            parameters_meta = parameters_meta_fmt,
            bundle_name = self.bundle_name,
            parameter_list = parameter_list.join(", "),
            calls = calls_fmt
        ))
    }
}

#[derive(Serialize, Deserialize, Default)]
pub struct InterpolatedParameter {
    id: String,
    name: String,
    description: String,
}
impl InterpolatedParameter {
    fn to_rudderlang(&self) -> Result<(String, String)> {
        let parameter_meta = format!(
            r#"{{ "name" = "{}", "id" = "{}", "description" = "{}" }}"#,
            self.name, self.id, self.description,
        );
        let parameter = self.name.replace("\"", "").replace(" ", "_").to_owned();

        Ok((parameter_meta, parameter))
    }
}

#[derive(Serialize, Deserialize)]
#[serde(untagged)]
enum MethodElem {
    MethodCall(MethodCall),
    MethodBlock(MethodBlock),
}

enum ReportingLogic {
    Focus(String),
    Sum,
    Worst,
}
// Display is used for conversion to metadata format
impl Display for ReportingLogic {
    fn fmt(&self, f: &mut Formatter<'_>) -> fmt::Result {
        match self {
            ReportingLogic::Focus(v) => write!(f, "\"focus:{}\'", v),
            ReportingLogic::Sum => write!(f, "\"sum\""),
            ReportingLogic::Worst => write!(f, "\"worst\""),
        }
    }
}
fn ser<S>(serializer: S, name: &str, value: Option<String>) -> std::result::Result<S::Ok, S::Error>
where
    S: Serializer,
{
    let field_count = if value.is_none() { 1 } else { 2 };
    let mut state = serializer.serialize_struct("ReportingLogic", field_count)?;
    state.serialize_field("type", name)?;
    if let Some(v) = value {
        state.serialize_field("value", &v)?;
    }
    state.end()
}
// serialize is used for conversion to/from json technique format
impl Serialize for ReportingLogic {
    fn serialize<S>(&self, serializer: S) -> std::result::Result<S::Ok, S::Error>
    where
        S: Serializer,
    {
        match self {
            ReportingLogic::Focus(v) => ser(serializer, "focus", Some(v.into())),
            ReportingLogic::Sum => ser(serializer, "sum", None),
            ReportingLogic::Worst => ser(serializer, "worst", None),
        }
    }
}

struct ReportingLogicVisitor;
impl<'de> Visitor<'de> for ReportingLogicVisitor {
    type Value = ReportingLogic;
    fn expecting(&self, formatter: &mut fmt::Formatter) -> fmt::Result {
        formatter.write_str("an object with a type and an optional value")
    }
    fn visit_map<A>(self, mut map: A) -> std::result::Result<Self::Value, A::Error>
    where
        A: MapAccess<'de>,
    {
        let mut real_map: HashMap<&str, &str> = HashMap::new();
        while let Some((k, v)) = map.next_entry()? {
            real_map.insert(k, v);
        }
        if let Some(&ty) = real_map.get("type") {
            if ty == "sum" {
                Ok(ReportingLogic::Sum)
            } else if ty == "worst" {
                Ok(ReportingLogic::Worst)
            } else if ty == "focus" {
                if let Some(&value) = real_map.get("value") {
                    Ok(ReportingLogic::Focus(value.into()))
                } else {
                    Err(serde::de::Error::custom(
                        "Focus reporting logic needs a value",
                    ))
                }
            } else {
                Err(serde::de::Error::custom(format!(
                    "Unknown reporting logic type {}",
                    ty
                )))
            }
        } else {
            Err(serde::de::Error::custom("Missing reporting logic type"))
        }
    }
}
// This is the trait that informs Serde how to deserialize MyMap.
impl<'de> Deserialize<'de> for ReportingLogic {
    fn deserialize<D>(deserializer: D) -> std::result::Result<Self, D::Error>
    where
        D: Deserializer<'de>,
    {
        // Instantiate our Visitor and ask the Deserializer to drive
        // it over the input data, resulting in an instance of MyMap.
        deserializer.deserialize_map(ReportingLogicVisitor)
    }
}

#[derive(Serialize, Deserialize)]
pub struct MethodBlock {
    reportingLogic: ReportingLogic,
    component: String,
    condition: String,
    calls: Vec<MethodElem>,
    id: String,
}
impl MethodBlock {
    fn to_rudderlang(&self, context: &Vec<&MethodBlock>, lib: &LanguageLib) -> Result<String> {
        let mut newContext = context.clone();
        let formatted_id = format!("@id = \"{}\"", self.id);
        let formatted_component = format!("@component = \"{}\"", self.component);
        let formatted_reporting_logic = format!("@reporting_logic = {}", self.reportingLogic);
        newContext.push(self);
        let childs = self
            .calls
            .iter()
            .map(|c| match c {
                MethodElem::MethodCall(callData) => callData.to_rudderlang(context, lib),
                MethodElem::MethodBlock(blockData) => blockData.to_rudderlang(context, lib),
            })
            .collect::<Result<Vec<String>>>()
            .map(|c| {
                format!(
                    "{}\n{}\n{}\n{{\n  {}\n  }}",
                    formatted_component,
                    formatted_id,
                    formatted_reporting_logic,
                    c.join("\n  ")
                )
            });

        let withCondition = if (self.condition == "" || self.condition == "any") {
            childs
        } else {
            let condition = format_condition(&self.condition, &lib)?;
            childs.map(|c| format!("if {} =>\n  {}", condition, c))
        };

        return withCondition;
    }
}
#[derive(Serialize, Deserialize)]
pub struct MethodCall {
    method_name: String,
    #[serde(rename = "class_context")]
    condition: String,
    component: String,
    #[serde(default)]
    parameters: Vec<Parameter>,
    #[serde(default = "generate_id")] // >=7.0
    id: String,
    #[serde(default = "default_disable_reporting", rename = "disableReporting")]
    reporting_disabled: bool,
}
fn generate_id() -> String {
    Uuid::new_v4().to_string()
}
fn default_disable_reporting() -> bool {
    false
}
// TODO parse content so interpolated variables are handled properly
fn format_condition(condition: &String, lib: &LanguageLib) -> Result<String> {
    lazy_static! {
        static ref CONDITION_RE: Regex = Regex::new(r"([\w${}.]+)").unwrap();
        static ref ANY_RE: Regex = Regex::new(r"(any\.)").unwrap();
        static ref CONDITION_FROM_RE: Regex =
            Regex::new(r"any\.\((\w*)_\$\{report_data\.canonified_directive_id\}_(true|false)")
                .unwrap();
    }
    // remove `any.` from condition
    // if opened / closed brackets count is balanced, then it is not an interpolated dot (`.`) but a logcial AND
    let mut bracket_balance: i8 = 0;
    let updated_condition = ANY_RE
        .replace_all(condition, "")
        .chars()
        .map(|c| {
            match c {
                '{' => bracket_balance += 1,
                '}' => bracket_balance -= 1,
                _ => (),
            };
            if c == '.' && bracket_balance == 0 {
                return '&';
            }
            c
        })
        .collect::<String>();
    let mut errs = Vec::new();
    // replace all matching words as classes
    let result =
        CONDITION_RE.replace_all(&updated_condition, |caps: &Captures| {
            match format_method(lib, &caps[1]) {
                Ok(s) => s,
                Err(e) => {
                    errs.push(e);
                    "".into()
                }
            }
        });
    if errs.is_empty() {
        Ok(result.into())
    } else {
        Err(Error::from_vec(errs))
    }
}

fn format_method(lib: &LanguageLib, cond: &str) -> Result<String> {
    // return known system class (formatted as cfengine system)
    if let Some(system) = lib.cf_system(cond) {
        return system;
    }
    // return method if outcome
    if let Some(outcome) = lib.cf_outcome(cond) {
        return Ok(outcome);
    }
    // else
    Err(Error::new(format!(
        "Don't know how to handle class '{}'",
        cond
    )))
}

impl MethodCall {
    fn to_rudderlang(&self, context: &Vec<&MethodBlock>, lib: &LanguageLib) -> Result<String> {
        let lib_method: LibMethod = lib.method_from_str(&self.method_name)?;

        let (mut params, template_vars) = self.format_parameters(&lib_method)?;
        let param_count = lib_method.resource.parameters.len() + lib_method.state.parameters.len();
        if params.len() != param_count {
            return Err(Error::new(format!(
                "Method {} is expected to have {} parameters, found {}",
                self.method_name,
                param_count,
                params.len()
            )));
        }
        let state_params: Vec<String> = params
            .drain(lib_method.resource.parameters.len()..)
            .collect();

        let class_param_index = lib_method.class_param_index();
        if self.parameters.len() < class_param_index {
            return Err(Error::new("Class param index is out of bounds".to_owned()));
        }
        let class_parameter = &self.parameters[class_param_index].value;
        let canonic_parameter = cfstrings::canonify(class_parameter);
        let outcome = format!(" as {}_{}", lib_method.class_prefix(), canonic_parameter);

        let call = format!(
            "{}({}).{}({})",
            lib_method.resource.name,
            params.join(", "),
            lib_method.state.name,
            state_params.join(", ")
        );
        let formatted_condition = if self.condition != "any" && self.condition != "" {
            format!("if {} => \n", format_condition(&self.condition, &lib)?)
        } else {
            "".to_string()
        };

        // only get original name, other aliases do not matter here
        let formatted_alias_metadata: Option<String> = lib_method
            .alias
            .map(|alias| format!("  @method_alias = \"{}\"\n", alias));

        let formatted_component = format!("  @component = \"{}\"\n", self.component);

        let formatted_id = format!("  @id = \"{}\"\n", self.id);
        let formatted_disable_reporting = if self.reporting_disabled {
            format!("  @disable_reporting = {}\n", self.reporting_disabled)
        } else {
            "".to_string()
        };

        // make an exception for condition_from_* method & condition generation
        if (lib_method.resource.name == "condition" && lib_method.state.name.starts_with("from_")) {
            return Ok(format!(
                "{}{}{}{}{}{}  let {} = {}_{}({})",
                template_vars.join("\n  "),
                formatted_condition,
                formatted_component,
                formatted_id,
                formatted_disable_reporting,
                formatted_alias_metadata.unwrap_or(String::new()),
                match class_parameter.strip_suffix("_${report_data.canonified_directive_id}") {
                    Some(variable) => variable,
                    None =>
                        return Err(Error::new(format!(
                            "Unexpected method 'condition_{}' class parameter content ({})",
                            lib_method.state.name, class_parameter
                        ))),
                },
                lib_method.resource.name,
                lib_method.state.name,
                state_params.join(", ")
            ));
        }

        Ok(format!(
            "{}{}{}{}{}{}  {}{}",
            template_vars.join("\n  "),
            formatted_condition,
            formatted_component,
            formatted_id,
            formatted_disable_reporting,
            formatted_alias_metadata.unwrap_or(String::new()),
            call,
            outcome
        ))
    }

    fn format_parameters(&self, lib_method: &LibMethod) -> Result<(Vec<String>, Vec<String>)> {
        let mut vars = Vec::new();
        let mut template_vars = Vec::new();
        for p in &self.sort_from_lib(lib_method) {
            match p.to_rudderlang(template_vars.len())? {
                (var, Some(template_var)) => {
                    vars.push(var);
                    template_vars.push(template_var);
                }
                (var, None) => vars.push(var),
            };
        }
        template_vars.push("".to_owned());
        Ok((vars, template_vars))
    }

    fn sort_from_lib(&self, lib_method: &LibMethod) -> Vec<Parameter> {
        let lib_params = lib_method
            .resource
            .parameters
            .iter()
            .chain(lib_method.state.parameters.iter())
            .map(|p| p.name.fragment())
            .collect::<Vec<&str>>();
        // sort parameters in lib order
        lib_params.iter().fold(Vec::new(), |mut vec, name| {

            // EXCEPTION: split variable_string_escaped resource parameters that appear to be joined from technique editor side
            if lib_method.resource.name == "variable" && lib_method.state.name == "string_escaped" {
                if self.parameters.len() == 1 && self.parameters[0].name == "name" {
                    let values: Vec<&str> = self.parameters[0].value.split(".").collect();
                    if values.len() == 2 {
                        return vec![
                            Parameter::new("prefix", values[0]),
                            Parameter::new("name", values[1]),
                        ];
                    }
                }
                panic!("variable_string_escaped method: expected a single parameter 'name' with a dot separated value");
            }
            for parameter in self.parameters.clone() {
                if &parameter.name == name {
                    vec.push(parameter);
                    return vec;
                }
            }
            panic!(
                "{}_{} method: parameter '{}' not found in expected method parameter names",
                lib_method.resource.name, lib_method.state.name, name
            );
        })
    }
}

#[derive(Serialize, Deserialize, Default)]
struct Resource {
    name: String,
    state: Parameter,
}
impl Resource {
    fn to_rudderlang(&self) -> Result<String> {
        unimplemented!()
        // not sure what it is yet, state = [ new, modified, deleted, untouched ]
        // no idea where / how resources are computed yet
    }
}

#[derive(Serialize, Deserialize, Default, Debug, Clone)]
struct Parameter {
    name: String,  // not used in rudder language
    value: String, // TODO convert into an UnescapedString
    #[serde(skip_deserializing, rename = "$errors")]
    // only useful when coupled with technique editor
    errors: Vec<String>,
}
impl Parameter {
    fn new(name: &str, value: &str) -> Self {
        Self {
            name: name.to_owned(),
            value: value.to_owned(),
            errors: Vec::new(),
        }
    }

    fn to_rudderlang(&self, template_len: usize) -> Result<(String, Option<String>)> {
        // rl v2 behavior should make use of this, for now, just a syntax lib
        if cfstrings::parse_string(&self.value).is_err() {
            return Err(Error::new(format!(
                "Invalid variable syntax in '{}'",
                self.value
            )));
        }
        Ok((format!("\"\"\"{}\"\"\"", self.value), None))
    }
}

// generic function that is used by rudder from multiple places to retrieve parameters in various formats
pub fn fetch_method_parameters<F, P>(ir: &IR2, s: &StateDeclaration, f: F) -> Vec<P>
where
    F: Fn(&str, &Value, Option<&toml::Value>) -> P,
{
    let resource = ir
        .resources
        .get(&s.resource)
        .expect(&format!("Called resource '{}' is not defined", *s.resource));
    let state = resource.states.get(&s.state).expect(&format!(
        "Called state '{}' is not defined for '{}'",
        s.state.fragment(),
        s.resource.fragment()
    ));
    let parameter_names = resource
        .parameters
        .iter()
        .chain(state.parameters.iter())
        .map(|p| p.name.fragment())
        .collect::<Vec<&str>>();
    let parameter_values = s
        .resource_params
        .iter()
        .chain(s.state_params.iter())
        .collect::<Vec<&Value>>();
    // there should be no issue here since
    // both iterators should be of same size bc parameters are checked at AST creation time
    parameter_names
        .iter()
        .zip(parameter_values)
        .map(|(name, value)| {
            let parameter_metadatas = state
                .metadata
                .get("parameter")
                .and_then(|metadatas| metadatas.get(name));
            f(name, &value, parameter_metadatas)
        })
        .collect::<Vec<P>>()
}

#[cfg(test)]
mod tests {
    use super::*;
    use pretty_assertions::assert_eq;

    #[derive(Serialize, Deserialize, PartialEq, Debug)]
    struct TestVersion {
        #[serde(
            serialize_with = "version_into_string",
            deserialize_with = "string_into_version"
        )] // >=6.1
        version: Version,
    }

    #[test]
    // tests if cli parameters are handled properly (setup behavior, forbids unwanted ones etc)
    fn technique_version() {
        let ok_1dot0 = r#"{"version": "1.0" }"#;
        let ok_big = r#"{"version": "255.255" }"#;
        let err_too_big = r#"{"version": "257.257" }"#;
        let err_negative = r#"{"version": "-2.2" }"#;
        let err_negative2 = r#"{"version": "2.-2" }"#;
        let err_too_many = r#"{"version": "2.1.0.0" }"#;
        let err_dot2 = r#"{"version": ".2" }"#;
        let err_empty = r#"{"version": "" }"#;
        let err_2 = r#"{"version": "2." }"#;
        let err_x = r#"{"version": "1.X" }"#;
        let err_char = r#"{"version": "XXXX" }"#;
        assert_eq!(
            TestVersion {
                version: Version(1, 0),
            },
            serde_json::from_str::<TestVersion>(ok_1dot0).unwrap(),
        );
        assert_eq!(
            TestVersion {
                version: Version(255, 255),
            },
            serde_json::from_str::<TestVersion>(ok_big).unwrap(),
        );
        assert!(serde_json::from_str::<TestVersion>(err_negative).is_err());
        assert!(serde_json::from_str::<TestVersion>(err_negative2).is_err());
        assert!(serde_json::from_str::<TestVersion>(err_too_big).is_err());
        assert!(serde_json::from_str::<TestVersion>(err_too_many).is_err());
        assert!(serde_json::from_str::<TestVersion>(err_dot2).is_err());
        assert!(serde_json::from_str::<TestVersion>(err_2).is_err());
        assert!(serde_json::from_str::<TestVersion>(err_empty).is_err());
        assert!(serde_json::from_str::<TestVersion>(err_x).is_err());
        assert!(serde_json::from_str::<TestVersion>(err_char).is_err());
    }
}
