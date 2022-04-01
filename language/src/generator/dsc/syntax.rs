// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use crate::error::{Error, Result};

use std::{fmt, str::FromStr};
use toml::map::Map as TomlMap;
use toml::Value as TomlValue;
/// This does not modelize full CFEngine syntax but only a subset of it, which is our
/// execution target, plus some Rudder-specific metadata in comments.

/// This subset should be safe, fast and readable (in that order).

/// Everything that does not have an effect on applied state
/// should follow a deterministic rendering process (component order, etc.)
/// This allows easy diff between produced files.

// No need to handle all calls and components, we only need to support the ones we are
// able to generate.

const TABS_WIDTH: usize = 2;

// format that automatically ident with the right offset
// adding the offset to every \n + the first char
macro_rules! indent_format {
    ($($arg:tt)*) => {{
        let mut is_here_string = false;
        format!($($arg)*)
            .split("\n")
            .map(|line| {
                let formatted_line = if !is_here_string {
                    format!("\n{:width$}{}", " ", line, width = TABS_WIDTH)
                } else {
                    format!("\n{}", line)
                };
                if line.trim().ends_with("@'") {
                    is_here_string = true;
                } else if line.trim().starts_with("'@") {
                    is_here_string = false;
                };
                formatted_line
            })
            .collect::<Vec<String>>()
            .concat()
    }}
}

// agent and generic method names are Pascebab-Case (mix of Pascal and Kebab)
fn pascebab_case(s: &str) -> String {
    let chars = s.chars();

    let mut pascebab = String::new();
    let mut is_next_uppercase = true;
    for c in chars {
        let next = match c {
            ' ' | '_' | '-' => {
                is_next_uppercase = true;
                String::from("-")
            }
            c => {
                if is_next_uppercase {
                    is_next_uppercase = false;
                    c.to_uppercase().to_string()
                } else {
                    c.to_string()
                }
            }
        };
        pascebab.push_str(&next);
    }
    pascebab
}

// everything else is standard pascal case (variables, parameters)
pub fn pascal_case(s: &str) -> String {
    let chars = s.chars();

    let mut pascal = String::new();
    let mut is_next_uppercase = true;
    for c in chars {
        let next = match c {
            ' ' | '_' | '-' => {
                is_next_uppercase = true;
                String::new()
            }
            c => {
                if is_next_uppercase {
                    is_next_uppercase = false;
                    c.to_uppercase().to_string()
                } else {
                    c.to_string()
                }
            }
        };
        pascal.push_str(&next);
    }
    pascal
}

pub fn quoted(s: &str) -> String {
    format!("\"{}\"", s)
}

#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug)]
pub enum ParameterKind {
    MethodName,
    ClassParameter,
    MethodParameter,
    ComponentName,
    ComponentKey,
    Message,
    ReportId,
    TechniqueName,
    Switch,
}
impl Default for ParameterKind {
    fn default() -> Self {
        ParameterKind::MethodParameter
    }
}

const PARAMETER_ORDERING: [ParameterKind; 9] = [
    ParameterKind::MethodName,
    ParameterKind::ClassParameter,
    ParameterKind::MethodParameter,
    ParameterKind::ComponentName,
    ParameterKind::ComponentKey,
    ParameterKind::Message,
    ParameterKind::ReportId,
    ParameterKind::TechniqueName,
    ParameterKind::Switch,
];

// Seems a bit dispropotionate to add a type only for a string variant but
// other DSC types will be supported in the future
#[derive(Clone, PartialEq, Eq, Debug)]
pub enum ParameterType {
    String,
    HereString,
}
impl Default for ParameterType {
    fn default() -> Self {
        Self::String
    }
}
impl FromStr for ParameterType {
    type Err = Error;

    fn from_str(ptype: &str) -> Result<Self> {
        match ptype {
            "string" => Ok(Self::String),
            "HereString" => Ok(Self::HereString),
            _ => Err(Error::new(format!("Could not parse format {}", ptype))),
        }
    }
}

#[derive(Clone, PartialEq, Eq, Default, Debug)]
pub struct Parameter {
    pub name: Option<String>,
    pub value: String,
    kind: ParameterKind,
    content_type: ParameterType,
}

impl Parameter {
    // general applications

    pub fn variable(
        name: Option<&str>,
        value: &str,
        kind: ParameterKind,
        content_type: ParameterType,
    ) -> Self {
        match name {
            Some(n) => Self {
                name: Some(pascal_case(n)),
                value: format!("${}", pascal_case(value)),
                kind,
                content_type,
            },
            None => Self {
                name: None,
                value: format!("${}", pascal_case(value)),
                kind,
                content_type,
            },
        }
    }

    pub fn string(
        name: Option<&str>,
        value: &str,
        kind: ParameterKind,
        content_type: ParameterType,
    ) -> Self {
        match name {
            Some(n) => Self {
                name: Some(pascal_case(n)),
                value: quoted(&value),
                kind,
                content_type,
            },
            None => Self {
                name: None,
                value: quoted(&value),
                kind,
                content_type,
            },
        }
    }

    pub fn raw(
        name: Option<&str>,
        value: &str,
        kind: ParameterKind,
        content_type: ParameterType,
    ) -> Self {
        match name {
            Some(n) => Self {
                name: Some(pascal_case(n)),
                value: value.to_owned(),
                kind,
                content_type,
            },
            None => Self {
                name: None,
                value: value.to_owned(),
                kind,
                content_type,
            },
        }
    }

    pub fn method_parameter(name: &str, value: &str, content_type: ParameterType) -> Self {
        Self {
            name: Some(pascal_case(name)),
            value: quoted(&value),
            kind: ParameterKind::MethodParameter,
            content_type,
        }
    }
}

impl fmt::Display for Parameter {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let formatted_value = match self.content_type {
            ParameterType::String => self.value.clone(),
            ParameterType::HereString => format!("@'\n{}\n'@", self.value.trim_matches('"')),
        };
        match (&self.name, self.kind) {
            (Some(name), ParameterKind::Switch) => write!(f, "-{}:{}", name, formatted_value),
            (Some(name), _) => write!(f, "-{} {}", name, formatted_value),
            (None, _) => write!(f, "{}", formatted_value),
        }
    }
}

#[derive(Clone, PartialEq, Eq, Default, Debug)]
pub struct Parameters(pub Vec<Parameter>);

impl Parameters {
    pub fn new() -> Self {
        Self(Vec::new())
    }

    pub fn sort(mut self) -> Self {
        self.sort_by_key(|p| {
            PARAMETER_ORDERING
                .iter()
                .position(|&kind| kind == p.kind)
                .unwrap()
        });
        self
    }

    pub fn method_name(mut self, res: &str, state: &str, method_alias: Option<String>) -> Self {
        let method_name = if let Some(method_alias_content) = method_alias {
            method_alias_content
        } else {
            format!("{}-{}", res, state)
        };
        let parameter = Parameter {
            name: None,
            value: pascebab_case(&method_name),
            kind: ParameterKind::MethodName,
            content_type: ParameterType::default(),
        };
        self.push(parameter);
        self
    }

    pub fn component_name(mut self, name: &str) -> Self {
        let parameter = Parameter::string(
            Some("ComponentName"),
            name,
            ParameterKind::ComponentName,
            ParameterType::default(),
        );
        self.push(parameter);
        self
    }

    pub fn component_key(mut self, value: &str) -> Self {
        let parameter = Parameter::raw(
            Some("ComponentKey"),
            value,
            ParameterKind::ComponentKey,
            ParameterType::default(),
        );
        self.push(parameter);
        self
    }

    pub fn mode(mut self) -> Self {
        let parameter = Parameter::variable(
            Some("AuditOnly"),
            "AuditOnly",
            ParameterKind::Switch,
            ParameterType::default(),
        );
        self.push(parameter);
        self
    }

    pub fn report_id(mut self) -> Self {
        let parameter = Parameter::variable(
            Some("ReportId"),
            "ReportId",
            ParameterKind::ReportId,
            ParameterType::default(),
        );
        self.push(parameter);
        self
    }

    pub fn technique_name(mut self) -> Self {
        let parameter = Parameter::variable(
            Some("TechniqueName"),
            "TechniqueName",
            ParameterKind::TechniqueName,
            ParameterType::default(),
        );
        self.push(parameter);
        self
    }

    pub fn method_parameter(mut self, name: &str, value: &str) -> Self {
        self.push(Parameter::method_parameter(
            name,
            value,
            ParameterType::default(),
        ));
        self
    }

    pub fn class_parameter(mut self, parameter: Parameter) -> Self {
        self.push(Parameter {
            name: parameter.name,
            value: parameter.value,
            kind: ParameterKind::ClassParameter,
            content_type: ParameterType::default(),
        });
        self
    }

    pub fn message(mut self, message: &str) -> Self {
        let parameter = Parameter::string(
            Some("Message"),
            message,
            ParameterKind::Message,
            ParameterType::default(),
        );
        self.push(parameter);
        self
    }
}

impl fmt::Display for Parameters {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "{}",
            self.0
                .iter()
                .map(|p| format!("{}", p))
                .collect::<Vec<String>>()
                .join(" ")
        )
    }
}
impl core::ops::Deref for Parameters {
    type Target = Vec<Parameter>;

    fn deref(self: &'_ Self) -> &'_ Self::Target {
        &self.0
    }
}
impl core::ops::DerefMut for Parameters {
    fn deref_mut(self: &'_ mut Self) -> &'_ mut Self::Target {
        &mut self.0
    }
}

#[derive(Clone, PartialEq, Eq, Hash)]
pub enum CallType {
    Variable,
    String,
    Slist,
    If(String),
    Else(String),
    MethodCall,
    ReportNa,
    Outcome,
    Log,
    Abort,
}

#[derive(Clone, PartialEq, Eq)]
pub struct Call {
    /// Comments in output file
    comments: Vec<String>,
    /// var name
    variable: Option<String>,
    /// What the call does
    components: Vec<(CallType, Option<String>)>,
}

/// Cosmetic simplification, skip always true conditions
const TRUE_CLASSES: [&str; 4] = ["true", "any", "concat(\"any\")", "concat(\"true\")"];

/// Cosmetic simplification, skip always true conditions
const FALSE_CLASSES: [&str; 4] = ["false", "!any", "concat(\"!any\")", "concat(\"false\")"];

impl Call {
    pub fn new() -> Self {
        Self {
            variable: None,
            components: Vec::new(),
            comments: Vec::new(),
        }
    }

    pub fn new_variable<T: Into<String>>(variable: T) -> Self {
        Self {
            variable: Some(variable.into()),
            components: Vec::new(),
            comments: Vec::new(),
        }
    }

    pub fn component<S: AsRef<str>>(mut self, component_type: CallType, value: S) -> Self {
        self.components
            .push((component_type, Some(value.as_ref().to_string())));
        self
    }

    /// Shortcut for building a string variable with a value to be quoted
    pub fn string<T: Into<String>, S: AsRef<str>>(name: T, value: S) -> Self {
        Call::string_raw(name, quoted(value.as_ref()))
    }

    /// Shortcut for building a string variable with a raw value
    pub fn string_raw<T: Into<String>, S: AsRef<str>>(name: T, value: S) -> Self {
        Call::new_variable(name).component(CallType::String, value.as_ref())
    }

    /// Shortcut for building a string variable with a raw value
    pub fn variable<T: Into<String>, S: AsRef<str>>(name: T, value: S) -> Self {
        Call::new_variable(name).component(CallType::Variable, value.as_ref())
    }

    pub fn method(parameters: Parameters) -> Self {
        Call::new_variable("$LocalClasses").component(
            CallType::MethodCall,
            format!(
                "Merge-ClassContext $LocalClasses $({}).get_item(\"classes\")",
                parameters
            ),
        )
    }

    pub fn report_na(parameters: Parameters) -> Self {
        Call::new().component(
            CallType::ReportNa,
            format!("_rudder_common_report_na {}", parameters),
        )
    }

    pub fn abort(parameters: Parameters) -> Self {
        Call::new().component(CallType::Abort, format!("_abort {}", parameters))
    }

    pub fn outcome(outcome: &str, parameters: Parameters) -> Self {
        Call::new().component(CallType::Outcome, format!("{} {}", outcome, parameters))
    }

    pub fn log(parameters: Parameters) -> Self {
        //       $ReportString = "An unknown error occurred while running command '$CommandName'"
        //       if ($AuditOnly) {
        //         [ComplianceStatus]::audit_error
        //     }
        //     else {
        //         [ComplianceStatus]::result_error
        //     }
        //   }
        //   $Classes = _rudder_common_report -TechniqueName $TechniqueName  -Status $State -ReportId $ReportId -ComponentName $ComponentName -ComponentKey $ComponentKey -Message $ReportString -MessageInfo $MessageInfo -MessageVerbose $MessageVerbose -report:$Report
        //   @{"status" = $State; "classes" = $Classes}

        // r#"    $State = [ComplianceStatus]::result_{}
        //$Classes = _rudder_common_report -TechniqueName $TechniqueName  -Status $State -ReportId $ReportId -ComponentName "TODO" -ComponentKey "TODO" -Message "TODO" -MessageInfo "TODO" -MessageVerbose "TODO" -report:"TODO"
        //@{{"status" = $State; "classes" = $Classes}}

        Call::new().component(CallType::Log, format!("TODO Log $({})", parameters))
    }

    /// Shortcut for adding a condition
    pub fn if_condition<T: AsRef<str>>(mut self, condition: T, call: String) -> Self {
        // Don't use always true conditions
        if !TRUE_CLASSES.iter().any(|c| c == &condition.as_ref()) {
            self.components
                .push((CallType::If(call), Some(quoted(condition.as_ref()))));
        }
        self
    }

    /// Shortcut for adding a condition
    pub fn else_condition<T: AsRef<str>>(mut self, condition: T, call: String) -> Self {
        // Don't use always true conditions
        if !FALSE_CLASSES.iter().any(|c| c == &condition.as_ref()) {
            // else if condition
            self.components
                .push((CallType::Else(call), Some(quoted(condition.as_ref()))));
            // TODO add else condition:
            // self.components.insert(CallType::Else, None);
        }
        self
    }

    pub fn comment<T: AsRef<str>>(mut self, comment: T) -> Self {
        self.comments.push(comment.as_ref().to_string());
        self
    }

    fn format(&self) -> String {
        let comment = self
            .comments
            .iter()
            .map(|c| indent_format!("# {}", c))
            .collect::<Vec<String>>()
            .concat();

        format!(
            "{}{}",
            comment,
            self.components
                .iter()
                .map(|(attr_type, content)| {
                    let fmt_call = match (attr_type, &self.variable) {
                        (CallType::If(call), _) => {
                            format!(
                                "$Class = {}\nif (Evaluate-Class $Class $LocalClasses $SystemClasses) {{{}\n}}",
                                content.as_ref().unwrap(),
                                call
                            )
                        },
                        (CallType::Else(call), _) => format!("else {{{}\n}}", call),
                        (_, Some(var)) => format!("{} = {}", var, content.as_ref().unwrap()),
                        (_, None) => content.as_ref().unwrap().to_owned()
                    };
                    indent_format!("{}", fmt_call)
                    // match &self.variable {
                    //     Some(var) => indent_format!("{} = {}", var, fmt_call),
                    //     None =>
                    // }
                })
                .collect::<Vec<String>>()
                .concat()
        )
    }
}

/// Helper for reporting boilerplate (reporting context + na report)
///
/// Generates a `Scope` including:
///
/// * method call
/// * reporting context
/// * n/a report
#[derive(Default)]
pub struct Method {
    // TODO check if correct
    resource: String,
    // TODO check if correct
    state: String,
    method_alias: Option<String>,
    // TODO check list of parameters
    parameters: Parameters,
    component: String,
    class_parameter: Parameter,
    condition: String,
    supported: bool,
    source: String,
    id: String,
}

// TODO send to condition method
// if condition == "any" {
//     Ok(call)
// } else {
//     let formatted_condition = &format!("\n  $Class = \"any.({})\"\n  if (Evaluate-Class $Class $LocalClasses $SystemClasses) {{", condition);
//     Ok(if is_dsc_gm {
//         format!(
//             "{}\n  {}\n  }} else {{\n  {}\n  }}",
//             formatted_condition, call, na_call
//         )
//     } else {
//         format!("{}\n  {}\n  }}\n", formatted_condition, call)
//     })
// }

impl Method {
    pub fn new() -> Self {
        Self {
            condition: "true".to_string(),
            ..Self::default()
        }
    }

    pub fn resource(self, resource: String) -> Self {
        Self { resource, ..self }
    }

    pub fn state(self, state: String) -> Self {
        Self { state, ..self }
    }

    pub fn parameters(self, parameters: Parameters) -> Self {
        Self { parameters, ..self }
    }

    pub fn source(self, source: &str) -> Self {
        Self {
            source: source.to_string(),
            ..self
        }
    }

    pub fn id(self, id: String) -> Self {
        Self { id, ..self }
    }

    pub fn alias(self, method_alias: Option<String>) -> Self {
        Self {
            method_alias,
            ..self
        }
    }

    pub fn supported(self, is_supported: bool) -> Self {
        Self {
            supported: is_supported,
            ..self
        }
    }

    pub fn class_parameter(self, class_parameter: Parameter) -> Self {
        Self {
            class_parameter: Parameter {
                name: class_parameter.name,
                value: class_parameter.value,
                content_type: class_parameter.content_type,
                kind: ParameterKind::ClassParameter,
            },
            ..self
        }
    }

    pub fn component(self, component: String) -> Self {
        Self { component, ..self }
    }

    pub fn condition(self, condition: String) -> Self {
        Self { condition, ..self }
    }

    pub fn build(self) -> Calls {
        assert!(!self.resource.is_empty());
        assert!(!self.state.is_empty());
        assert!(self.class_parameter.name.is_some());
        // assert!(!self.class_parameter.value.is_empty());

        // Does the method have a real condition?
        let has_condition = !TRUE_CLASSES.iter().any(|c| c == &self.condition);

        let report_id = Call::variable("$ReportId", format!("$ReportIdBase+\"{}\"", &self.id));

        let method_call = Call::method(
            Parameters::from(self.parameters.clone())
                .method_name(&self.resource, &self.state, self.method_alias)
                .class_parameter(self.class_parameter.clone())
                .component_name(&self.component)
                .report_id()
                .technique_name()
                .mode()
                .sort(),
        );
        let na_report = Call::report_na(
            Parameters::new()
                .component_name(&self.component)
                .component_key(&self.class_parameter.value)
                .message("Not applicable")
                .report_id()
                .technique_name()
                .mode()
                .sort(),
        );

        match self.supported {
            true => {
                if has_condition {
                    let na_condition = format!(
                        "canonify(\"${{class_prefix}}_{}_{}_{}\")",
                        self.resource, self.state, self.class_parameter.value
                    );
                    let condition_format = method_call.format();
                    vec![
                        report_id,
                        Call::new()
                            .if_condition(self.condition.clone(), condition_format)
                            .else_condition(&self.condition, na_report.format()),
                        // Call::method(Parameters::new()
                        //     .class_parameter(self.class_parameter.clone())
                        //     .message(&format!(
                        //         "Skipping method '{}' with key parameter '{}' since condition '{}' is not reached",
                        //         &self.component,
                        //         report_param_name,
                        //         self.condition)
                        //     )
                        //     .message(&na_condition)
                        //     .message(&na_condition)
                        //     .message("@{args}")
                        // ),
                    ]
                } else {
                    vec![report_id, method_call]
                }
            }
            false => vec![report_id, na_report],
        }
    }
}

// Callss allow to wrap Calls in brackets and help with clarity
type Calls = Vec<Call>;

#[derive(Clone, PartialEq, Eq)]
pub struct Function {
    name: String,
    // Order matters!
    parameters: Vec<String>,
    calls: Vec<Calls>,
}

impl Function {
    pub fn agent<T: Into<String>>(name: T) -> Self {
        Self {
            name: pascebab_case(&name.into()),
            parameters: Vec::new(),
            calls: Vec::new(),
        }
    }

    pub fn parameters(self, parameters: Vec<String>) -> Self {
        Self { parameters, ..self }
    }

    pub fn scope(mut self, call_group: Calls) -> Self {
        self.push_scope(call_group);
        self
    }

    pub fn push_scope(&mut self, call_group: Calls) {
        if call_group.is_empty() {
            return;
        }
        self.calls.push(call_group);
    }
}

impl fmt::Display for Function {
    // localclasses and resourcesDir should ne be hardcoded
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let boilerplate = match !self.parameters.is_empty() {
            true if !self.calls.is_empty() => format!(
                "  [CmdletBinding()]\n  param (\n    {}\n  )",
                self.parameters.join(",\n    ")
            ),
            true => "  [CmdletBinding()]\n  param ()".to_owned(),
            false => String::new(),
        };
        writeln!(
            f,
            r#"function {} {{
{}"#,
            self.name, boilerplate
        )?;

        for group in self.calls.iter() {
            for call in group {
                write!(f, "{}", call.format())?;
            }
        }
        writeln!(f, "\n}}")
    }
}

pub struct Policy {
    functions: Vec<Function>,
    name: Option<String>,
    version: Option<String>,
}

impl Policy {
    pub fn new() -> Self {
        Self {
            name: None,
            version: None,
            functions: Vec::new(),
        }
    }

    pub fn name<T: Into<String>>(self, name: T) -> Self {
        Self {
            name: Some(name.into()),
            ..self
        }
    }

    pub fn version<T: Into<String>>(self, version: T) -> Self {
        Self {
            version: Some(version.into()),
            ..self
        }
    }

    pub fn function(mut self, function: Function) -> Self {
        self.functions.push(function);
        self
    }
}

impl fmt::Display for Policy {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        if self.name.is_some() {
            writeln!(f, "# @name {}", self.name.as_ref().unwrap())?;
        }
        if self.version.is_some() {
            writeln!(f, "# @version {}", self.version.as_ref().unwrap())?;
        }

        // let mut sorted_functions = self.functions.clone();
        // sorted_functions.sort_by(|a, b| b.name.cmp(&a.name));
        // for function in sorted_functions {
        for function in &self.functions {
            write!(f, "\n{}", function)?;
        }
        Ok(())
    }
}

// taken from cfengine generator, still WIP translating it into dsc
#[cfg(test)]
mod tests {
    use super::*;
    use pretty_assertions::assert_eq;

    #[test]
    fn format_call() {
        assert_eq!(Call::new().format(), "");
        // TODO Conditions
        assert_eq!(
            Call::new().comment("test".to_string()).format(),
            "\n  # test"
        );
        assert_eq!(
            Call::string("test", "plop")
                .if_condition("debian", Call::new().comment("empty").format())
                .format(),
            r#"
  test = "plop"
  $Class = "debian"
  if (Evaluate-Class $Class $LocalClasses $SystemClasses) {
    # empty
  }"#
        );
    }

    #[test]
    fn condition_dsc_method() {
        assert_eq!(
            Function::agent("test")
                .scope(
                    Method::new()
                        .resource("directory".to_string())
                        .state("absent".to_string())
                        .class_parameter(Parameter::method_parameter(
                            "p0",
                            "parameter With CASE",
                            ParameterType::default()
                        ))
                        .parameters(Parameters::new().method_parameter("p1", "vim"))
                        .component("component".to_string())
                        .condition("windows".to_string())
                        .id("id".to_string())
                        .supported(true) // must be set manually since generic method check happens elsewhere
                        .build()
                )
                .to_string(),
            r#"function Test {


  $ReportId = $ReportIdBase+"id"
  $Class = "windows"
  if (Evaluate-Class $Class $LocalClasses $SystemClasses) {
    $LocalClasses = Merge-ClassContext $LocalClasses $(Directory-Absent -P0 "parameter With CASE" -P1 "vim" -ComponentName "component" -ReportId $ReportId -TechniqueName $TechniqueName -AuditOnly:$AuditOnly).get_item("classes")
  }
  else {
    _rudder_common_report_na -ComponentName "component" -ComponentKey "parameter With CASE" -Message "Not applicable" -ReportId $ReportId -TechniqueName $TechniqueName -AuditOnly:$AuditOnly
  }
}
"#
        );
    }

    #[test]
    fn condition_method_not_applicable() {
        assert_eq!(
            Function::agent("test")
                .scope(
                    Method::new()
                        .resource("package".to_string())
                        .state("present".to_string())
                        .class_parameter(Parameter::method_parameter(
                            "p0",
                            "parameter With CASE",
                            ParameterType::default()
                        ))
                        .parameters(Parameters::new().method_parameter("p1", "vim"))
                        .component("component".to_string())
                        .condition("debian".to_string())
                        .id("id".to_string())
                        .build()
                )
                .to_string(),
            r#"function Test {


  $ReportId = $ReportIdBase+"id"
  _rudder_common_report_na -ComponentName "component" -ComponentKey "parameter With CASE" -Message "Not applicable" -ReportId $ReportId -TechniqueName $TechniqueName -AuditOnly:$AuditOnly
}
"#
        );
    }
    // TODO : add these cases
    // $Class = "debian" => package_present(vim)
    // else => _classes_noop(canonify(\"${class_prefix}_package_present_parameter\")),
    // $Class = "debian"
    // log_rudder(\"Skipping method \'component\' with key parameter \'parameter\' since condition \'debian\' is not reached\", \"parameter\", canonify(\"${class_prefix}_package_present_parameter\"), canonify(\"${class_prefix}_package_present_parameter\"), @{args}),

    #[test]
    fn format_function() {
        assert_eq!(
            Function::agent("test").to_string(),
            "function Test {\n\n\n}\n"
        );
        assert_eq!(
            Function::agent("test")
                // TODO function parameters should not be taken as strings, but instead automatically generated
                .parameters(vec!["file".to_string(), "lines".to_string()])
                .scope(vec![Call::method(Parameters::new())])
                .to_string(),
            // TODO format parameters properly
            r#"function Test {
  [CmdletBinding()]
  param (
    file,
    lines
  )

  $LocalClasses = Merge-ClassContext $LocalClasses $().get_item("classes")
}
"#
        );
    }

    #[test]
    fn format_policy() {
        let mut meta = std::collections::HashMap::new();
        meta.insert("extra".to_string(), "plop".to_string());

        assert_eq!(
            Policy::new()
                .name("test")
                .version("1.0")
                .function(Function::agent("test"))
                .to_string(),
            "# @name test\n# @version 1.0\n\nfunction Test {\n\n\n}\n"
        );
    }
}
