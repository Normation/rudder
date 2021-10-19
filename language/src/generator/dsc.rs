// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

mod syntax;

use super::Generator;
use crate::{
    command::CommandResult,
    error::*,
    generator::dsc::syntax::{
        pascal_case, Call, Function, Method, Parameter, ParameterType, Parameters, Policy,
    },
    generator::Format,
    ir::{
        context::Type, enums::EnumExpressionPart, ir2::IR2, resource::*, value::*,
        variable::VariableDef,
    },
    parser::*,
    technique::fetch_method_parameters,
};
use std::{
    collections::HashMap,
    convert::TryFrom,
    path::{Path, PathBuf},
    str::FromStr,
};
use toml::Value as TomlValue;

type Condition = String;

/*
    DSC parameter types:

    [int]       32-bit signed integer                   => no match
    [long]  	64-bit signed integer                   => no match
    [string] 	Fixed-length string of Unicode chars    => corresponds to our String type
    [char]  	Unicode 16-bit character                => no match
    [bool]  	True/false value                        => corresponds to our Boolean type
    [byte]  	8-bit unsigned integer                  => no match
    [double]  	Double-precision 64-bit fp numbers      => corresponds to our Number type
    [decimal]  	128-bit decimal value                   => no match
    [single]  	Single-precision 32-bit fp numbers      => no match
    [array]  	Array of values                         =>
    [xml]       Xmldocument object                      => no match
    [hashtable] Hashtable object (~Dictionary~)         =>
*/

pub struct DSC {
    // list of already formatted expression in current case
    current_cases: Vec<String>,
    // match enum local variables with class prefixes
    var_prefixes: HashMap<String, String>,
    // already used class prefix
    prefixes: HashMap<String, u32>,
    // condition to add for every other condition for early return
    return_condition: Option<String>,
}

impl DSC {
    pub fn new() -> Self {
        Self {
            current_cases: Vec::new(),
            var_prefixes: HashMap::new(),
            prefixes: HashMap::new(),
            return_condition: None,
        }
    }

    fn new_var(&mut self, prefix: &str) {
        let id = self.prefixes.get(prefix).unwrap_or(&0) + 1;
        self.prefixes.insert(prefix.to_string(), id);
        let var = format!("{}{}", prefix, id);
        self.var_prefixes.insert(prefix.to_string(), var);
    }

    fn reset_cases(&mut self) {
        // TODO this make case in case fail
        self.current_cases = Vec::new();
    }

    fn reset_context(&mut self) {
        self.var_prefixes = HashMap::new();
        self.return_condition = None;
    }

    fn format_case_expr(
        &mut self,
        gc: &IR2,
        case: &EnumExpressionPart,
        parentCondition: Option<String>,
    ) -> Result<String> {
        let expr = match case {
            EnumExpressionPart::And(e1, e2) => {
                let mut lexpr = self.format_case_expr(gc, e1, None)?;
                let mut rexpr = self.format_case_expr(gc, e2, None)?;
                if lexpr.contains("|") {
                    lexpr = format!("({})", lexpr);
                }
                if rexpr.contains("|") {
                    rexpr = format!("({})", rexpr);
                }
                format!("{}.{}", lexpr, rexpr)
            }
            EnumExpressionPart::Or(e1, e2) => format!(
                "{}|{}",
                self.format_case_expr(gc, e1, None)?,
                self.format_case_expr(gc, e2, None)?
            ),
            // TODO what about classes that have not yet been set ? can it happen ?
            EnumExpressionPart::Not(e1) => {
                let mut expr = self.format_case_expr(gc, e1, None)?;
                if expr.contains("|") || expr.contains(".") {
                    expr = format!("({})", expr);
                }
                format!("!{}", expr)
            }
            EnumExpressionPart::Compare(var, e, item) => {
                if let Some(true) = gc.enum_list.enum_is_global(*e) {
                    gc.enum_list.get_cfengine_item_name(*var, *item)
                } else {
                    // if var is a foreign variable, output it as it is
                    if e.fragment() == "boolean" && item.fragment() == "true" {
                        var.fragment().to_owned()
                    } else {
                        // Temporary keep this piece of code in case it has a purpose
                        // // concat var name + item
                        // let prefix = &self.var_prefixes[var.fragment()];
                        // // TODO there may still be some conflicts with var or enum containing '_'
                        // format!("{}_{}_{}", prefix, e.fragment(), item.fragment())
                        // format!("{}_{}", var.fragment(), item.fragment())
                        format!(r#"{}_{}"#, var.fragment(), item.fragment())
                    }
                }
            }
            EnumExpressionPart::RangeCompare(_var, _e, _item1, _item2) => unimplemented!(), // TODO
            EnumExpressionPart::Default(_) => {
                // extract current cases and build an opposite expression
                if self.current_cases.is_empty() {
                    "any".to_string()
                } else {
                    format!("!({})", self.current_cases.join("."))
                }
            }
            EnumExpressionPart::NoDefault(_) => "".to_string(),
        };

        let res: String = match parentCondition {
            None => expr,
            Some(parent) => format!("({}).({})", parent, expr),
        };
        return Ok(res);
    }

    // TODO simplify expression and remove uselesmap_strings_results conditions for more readable cfengine
    // TODO underscore escapement
    // TODO how does cfengine use utf8
    // TODO variables
    // TODO comments and metadata
    // TODO use in_class everywhere
    fn format_statement(
        &mut self,
        gc: &IR2,
        res_def: &ResourceDef,
        state_def: &StateDef,
        st: &Statement,
        condition: Option<String>,
    ) -> Result<Vec<Call>> {
        // get variables to try to get the proper parameter value
        let mut variables: HashMap<&Token, &VariableDef> = HashMap::new();
        for st_from_list in &state_def.statements {
            // variables declared after the current statemnt are not defined at this point
            if st_from_list == st {
                break;
            } else if let Statement::VariableDefinition(v) = st_from_list {
                variables.insert(&v.name, v);
            }
        }
        variables.extend(res_def.variable_definitions.get());
        variables.extend(&gc.variable_definitions);

        match st {
            Statement::ConditionVariableDefinition(var) => {
                let method_name = &format!("{}_{}", var.resource.fragment(), var.state.fragment());
                let state_decl = var.to_method();
                let inner_state_def = gc.get_state_def(&state_decl.resource, &state_decl.state)?;

                let mut parameters =
                    fetch_method_parameters(gc, &state_decl, |name, value, parameter_metadatas| {
                        let content_type = parameter_metadatas
                            .and_then(|param_metadatas| param_metadatas.get("type"))
                            .and_then(|r#type| r#type.as_str())
                            .and_then(|type_str| ParameterType::from_str(type_str).ok())
                            .unwrap_or(ParameterType::default());
                        let string_value = &self
                            .value_to_string(value, &variables, false)
                            .expect("Value is not formatted correctly");
                        Parameter::method_parameter(name, string_value, content_type)
                    });

                let class_param_index = inner_state_def.class_parameter_index(method_name)?;
                if parameters.len() < class_param_index {
                    return Err(Error::new(format!(
                        "Class param index is out of bounds for {}",
                        method_name
                    )));
                }

                // EXCEPTION: reunite variable_string_escaped resource parameters that appear to be joined from cfengine side
                if res_def.name == "variable" && state_def.name == "string_escaped" {
                    parameters[0].value =
                        format!("{}.{}", parameters[0].value, parameters[1].value);
                    parameters.remove(1);
                }

                let class_param = parameters.remove(class_param_index);

                let is_dsc_supported = match inner_state_def.supported_targets(method_name) {
                    Ok(targets) => targets.contains(&"dsc".to_owned()),
                    Err(_) => true,
                };

                let component = match var
                    .metadata
                    .get("component")
                    .or(inner_state_def.metadata.get("name"))
                {
                    Some(TomlValue::String(s)) => s.to_owned(),
                    _ => method_name.to_owned(),
                };

                Ok(Method::new()
                    .resource(var.resource.fragment().to_string())
                    .state(var.state.fragment().to_string())
                    .parameters(Parameters(parameters))
                    .class_parameter(class_param.clone())
                    .component(component)
                    .supported(is_dsc_supported)
                    .condition(
                        condition.map_or_else(|| String::from("any"), |x| self.format_condition(x)),
                    ) // TODO
                    .build())
            }
            Statement::StateDeclaration(sd) => {
                let inner_state_def = gc.get_state_def(&sd.resource, &sd.state)?;
                let method_name = &format!("{}_{}", sd.resource.fragment(), sd.state.fragment());

                if let Some(var) = sd.outcome {
                    self.new_var(&var);
                }

                let mut parameters =
                    fetch_method_parameters(gc, sd, |name, value, parameter_metadatas| {
                        let content_type = parameter_metadatas
                            .and_then(|param_metadatas| param_metadatas.get("type"))
                            .and_then(|r#type| r#type.as_str())
                            .and_then(|type_str| ParameterType::from_str(type_str).ok())
                            .unwrap_or(ParameterType::default());
                        let string_value = &self
                            .value_to_string(value, &variables, false)
                            .expect("Value is not formatted correctly");
                        Parameter::method_parameter(name, string_value, content_type)
                    });
                let class_param_index = inner_state_def.class_parameter_index(method_name)?;
                if parameters.len() < class_param_index {
                    return Err(Error::new(format!(
                        "Class param index is out of bounds for {}",
                        method_name
                    )));
                }

                // EXCEPTION: reunite variable_string_escaped resource parameters that appear to be joined from cfengine side
                if method_name == "variable_string_escaped" {
                    let prefix = parameters[0].value.strip_suffix("\"").unwrap(); // parameters are quoted so unwrap will work without issue
                    let name = parameters[1].value.strip_prefix("\"").unwrap(); // parameters are quoted so unwrap will work without issue
                    parameters[0].value = format!("{}.{}", prefix, name);
                    parameters.remove(1);
                }

                let class_param = parameters.remove(class_param_index);

                let is_dsc_supported = match inner_state_def.supported_targets(method_name) {
                    Ok(targets) => targets.contains(&"dsc".to_owned()),
                    Err(_) => true,
                };

                let component = match sd
                    .metadata
                    .get("component")
                    .or(inner_state_def.metadata.get("name"))
                {
                    Some(TomlValue::String(s)) => s.to_owned(),
                    _ => method_name.to_owned(),
                };

                Ok(Method::new()
                    .resource(sd.resource.fragment().to_string())
                    .state(sd.state.fragment().to_string())
                    .alias(
                        sd.metadata
                            .get("method_alias") // might simply be the class_prefix
                            .and_then(|v| v.as_str())
                            .map(String::from),
                    )
                    .parameters(Parameters(parameters))
                    .class_parameter(class_param.clone())
                    .component(component)
                    .supported(is_dsc_supported)
                    .condition(
                        condition.map_or_else(|| String::from("any"), |x| self.format_condition(x)),
                    ) // TODO
                    .source(sd.source.fragment())
                    .build())
            }
            Statement::Case(_case, vec) => {
                self.reset_cases();
                let mut res = vec![];

                for (case, st) in vec {
                    let case_exp =
                        self.format_case_expr(gc, &case.expression, condition.clone())?;
                    res.append(&mut self.format_statement(
                        gc,
                        res_def,
                        state_def,
                        st,
                        Some(case_exp),
                    )?);
                }
                Ok(res)
            }
            Statement::Fail(msg) => Ok(vec![Call::abort(
                Parameters::new()
                    .message("policy_fail")
                    .message(&self.value_to_string(msg, &variables, true)?),
            )]),
            Statement::LogDebug(msg) => Ok(vec![Call::log(
                Parameters::new()
                    .message("log_debug")
                    .message(&self.value_to_string(msg, &variables, true)?)
                    .message("None")
                    // TODO: unique class prefix
                    .message("log_debug"),
            )]),
            Statement::LogInfo(msg) => Ok(vec![Call::log(
                Parameters::new()
                    .message("log_info")
                    .message(&self.value_to_string(msg, &variables, true)?)
                    .message("None")
                    // TODO: unique class prefix
                    .message("log_info"),
            )]),
            Statement::LogWarn(msg) => Ok(vec![Call::log(
                Parameters::new()
                    .message("log_warn")
                    .message(&self.value_to_string(msg, &variables, true)?)
                    .message("None")
                    // TODO: unique class prefix
                    .message("log_warn"),
            )]),
            Statement::Return(outcome) => {
                // handle end of bundle
                self.return_condition = Some(match self.current_cases.last() {
                    None => "!any".into(),
                    Some(c) => format!("!({})", c),
                });
                let status = match outcome.fragment() {
                    "kept" => "success",
                    "repaired" => "repaired",
                    _ => "error",
                };
                Ok(vec![Call::outcome(status, Parameters::new())]) // use bundle ?
            }
            Statement::Noop => Ok(Vec::new()),
            // TODO Statement::VariableDefinition()
            Statement::VariableDefinition(_) => Ok(Vec::new()),
            Statement::BlockDeclaration(def) => {
                let mut res = vec![];

                for st in &def.childs {
                    res.append(&mut self.format_statement(
                        gc,
                        res_def,
                        state_def,
                        &st,
                        condition.clone(),
                    )?);
                }

                Ok(res)
            }
        }
    }

    fn value_to_string(
        &self,
        value: &Value,
        variables: &HashMap<&Token, &VariableDef>,
        string_delim: bool,
    ) -> Result<String> {
        let delim = if string_delim { "\"" } else { "" };
        Ok(match value {
            Value::String(s) => format!(
                "{}{}{}",
                delim,
                s.data
                    .iter()
                    .map(|t| match t {
                        PInterpolatedElement::Static(s) => {
                            // replace ${const.xx}
                            s.replace("$", "${const.dollar}")
                                .replace("\\", "\\\\") // backslash escape
                                .replace("\"", "\\\"") // quote escape
                                .replace("\\n", "${const.n}")
                                .replace("\\r", "${const.r}")
                                .replace("\\t", "${const.t}")
                        }
                        PInterpolatedElement::Variable(v) => {
                            // translate variable name
                            format!("${{{}}}", v)
                        }
                    })
                    .collect::<Vec<String>>()
                    .concat(),
                delim
            ),
            Value::Float(_, n) => format!("{}", n),
            Value::Integer(_, n) => format!("{}", n),
            Value::Boolean(_, b) => format!("{}", b),
            Value::EnumExpression(_) => unimplemented!(),
            Value::List(l) => format!(
                "[ {} ]",
                map_strings_results(l.iter(), |x| self.value_to_string(x, variables, true), ",")?
            ),
            Value::Struct(s) => format!(
                "{{ {} }}",
                map_strings_results(
                    s.iter(),
                    |(x, y)| Ok(format!(
                        r#""{}":{}"#,
                        x,
                        self.value_to_string(y, variables, true)?
                    )),
                    ","
                )?
            ),
            Value::Variable(v) => {
                if let Some(var) = variables.get(v).and_then(|var_def| {
                    var_def
                        .value
                        .first_value()
                        .and_then(|v| self.value_to_string(v, variables, string_delim))
                        .ok()
                }) {
                    return Ok(var);
                }
                warn!(
                    "The variable {} isn't recognized, so we can't guarantee it will be defined when evaluated (4)",
                    v.fragment()
                );
                format!("{}${{{}}}{}", delim, v.fragment(), delim)
            }
        })
    }

    fn format_condition(&mut self, condition: String) -> Condition {
        self.current_cases.push(condition.clone());
        match &self.return_condition {
            None => condition,
            Some(c) => format!("({}).({})", c, condition),
        }
    }

    pub fn format_param_type(&self, type_: &Type) -> String {
        // TODO add HereString type (== RawString) to prevent interpolation, used in 1 DSC method
        String::from(match type_ {
            Type::String => "String",
            Type::Float => "Double",
            Type::Integer => "Int",
            Type::Boolean => "Switch", // do not use powershell bools, use switches instead by default
            Type::List => "Array",     // actually needs implementation work
            Type::Struct(_) => "Hashtable", // actually needs implementation work
            Type::Enum(_) => unimplemented!(),
        })
    }
}

impl Generator for DSC {
    // TODO methods differ if this is a technique generation or not
    fn generate(
        &mut self,
        gc: &IR2,
        source_file: &str,
        dest_file: Option<&Path>,
        policy_metadata: bool,
    ) -> Result<Vec<CommandResult>> {
        let mut files: Vec<CommandResult> = Vec::new();
        // TODO add global variable definitions
        for (resource_name, resource) in gc.resources.iter() {
            for (state_name, state) in resource.states.iter() {
                // This condition actually rejects every file that is not the input filename
                // therefore preventing from having an output in another directory
                // Solutions: check filename rather than path, or accept everything that is not from crate root lib
                if state.is_dependency {
                    // means it's a lib file, not the file we want to generate
                    continue;
                }
                let resource_name = resource_name
                    .strip_prefix("technique_")
                    .or_else(|| {
                        if policy_metadata {
                            Some(resource_name)
                        } else {
                            None
                        }
                    })
                    .ok_or_else(|| {
                        err!(
                            Token::new(resource.name, ""),
                            "Technique resource must start with 'technique_'"
                        )
                    })?;
                self.reset_context();

                // get parameters, default params are hardcoded for now
                let mut formatted_parameters: Vec<String> = vec![
                    "[Parameter(Mandatory=$True)]\n    [String]$ReportId".to_owned(),
                    "[Parameter(Mandatory=$True)]\n    [String]$TechniqueName".to_owned(),
                ];
                // ad gm parameters
                formatted_parameters.extend(
                    resource
                        .parameters
                        .iter()
                        .chain(state.parameters.iter())
                        .map(|p| {
                            format!(
                                "[Parameter(Mandatory=$True)]\n    [{}]${}",
                                &self.format_param_type(&p.type_),
                                pascal_case(p.name.fragment())
                            )
                        })
                        .collect::<Vec<String>>(),
                );
                // parameter is not mandatory (implicit)
                // Switches should be put last
                formatted_parameters.push("[Switch]$AuditOnly".to_owned());

                let fn_name = if state_name.fragment() == "technique" {
                    resource_name.to_owned()
                } else {
                    format!("{} {}", resource_name, state_name.fragment(),)
                };

                let mut function = Function::agent(fn_name.clone())
                    .parameters(formatted_parameters.clone())
                    // Standard variables for all techniques
                    .scope(vec![
                        Call::variable("$LocalClasses", "New-ClassContext"),
                        Call::variable("$ResourcesDir", "$PSScriptRoot + \"\\resources\""),
                    ]);

                for methods in state.statements.iter().flat_map(|statement| {
                    self.format_statement(gc, resource, state, statement, None)
                }) {
                    function.push_scope(methods);
                }

                let extract = |name: &str| {
                    resource
                        .metadata
                        .get(name)
                        .and_then(|v| match v {
                            TomlValue::String(s) => Some(s.to_owned()),
                            _ => None,
                        })
                        .unwrap_or_else(|| "unknown".to_string())
                };

                let content: String = if policy_metadata {
                    Policy::new()
                        .name(extract("name"))
                        .version(extract("version"))
                        .function(function)
                        .to_string()
                } else {
                    function.to_string()
                };
                files.push(CommandResult::new(
                    Format::DSC,
                    dest_file.map(|o| PathBuf::from(o)),
                    Some(content),
                ));
            }
        }
        Ok(files)
    }
}
