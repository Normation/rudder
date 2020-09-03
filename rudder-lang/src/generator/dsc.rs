// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

mod syntax;

use super::Generator;
use crate::{
    error::*,
    generator::dsc::syntax::{
        pascebab_case, Call, Function, Method, Parameter, Parameters, Policy,
    },
    ir::{context::Type, enums::EnumExpressionPart, ir2::IR2, resource::*, value::*},
    parser::*,
    technique::fetch_method_parameters,
    ActionResult, Format,
};
use std::{
    collections::HashMap,
    path::{Path, PathBuf},
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

    fn parameter_to_dsc(&self, param: &Value, param_name: &str) -> Result<String> {
        Ok(match param {
            Value::String(s) => {
                // TODO integrate name to parameters
                let param_value = s.format(
                    |x: &str| {
                        x.replace("\\", "\\\\") // backslash escape
                            .replace("\"", "\\\"") // quote escape
                            .replace("$", "${const.dollar}")
                    }, // dollar escape
                    |y: &str| format!("${{{}}}", y), // variable inclusion
                );
                format!(r#"-{} "{}""#, param_name, param_value)
            }
            Value::Float(_, _) => unimplemented!(),
            Value::Integer(_, _) => unimplemented!(),
            Value::Boolean(_, _) => unimplemented!(),
            Value::EnumExpression(_e) => "".into(), // TODO
            Value::List(_) => unimplemented!(),
            Value::Struct(_) => unimplemented!(),
        })
    }

    fn format_case_expr(&mut self, gc: &IR2, case: &EnumExpressionPart) -> Result<String> {
        Ok(match case {
            EnumExpressionPart::And(e1, e2) => {
                let mut lexpr = self.format_case_expr(gc, e1)?;
                let mut rexpr = self.format_case_expr(gc, e2)?;
                if lexpr.contains(" -or ") {
                    lexpr = format!("({})", lexpr);
                }
                if rexpr.contains(" -or ") {
                    rexpr = format!("({})", rexpr);
                }
                format!("{} -and {}", lexpr, rexpr)
            }
            EnumExpressionPart::Or(e1, e2) => format!(
                "{} -or {}",
                self.format_case_expr(gc, e1)?,
                self.format_case_expr(gc, e2)?
            ),
            // TODO what about classes that have not yet been set ? can it happen ?
            EnumExpressionPart::Not(e1) => {
                let mut expr = self.format_case_expr(gc, e1)?;
                if expr.contains(" -or ") || expr.contains(" -and ") {
                    expr = format!("!({})", expr);
                }
                format!("!{}", expr)
            }
            EnumExpressionPart::Compare(var, e, item) => {
                if let Some(true) = gc.enum_list.enum_is_global(*e) {
                    // We probably need some translation here since not all enums are available in cfengine (ex debian_only)
                    item.fragment().to_string() // here
                } else {
                    // Temporary keep this piece of code in case it has a purpose
                    // // concat var name + item
                    // let prefix = &self.var_prefixes[var.fragment()];
                    // // TODO there may still be some conflicts with var or enum containing '_'
                    // format!("{}_{}_{}", prefix, e.fragment(), item.fragment())
                    format!("{}_{}", var.fragment(), item.fragment())
                }
            }
            EnumExpressionPart::RangeCompare(_var, _e, _item1, _item2) => unimplemented!(), // TODO
            EnumExpressionPart::Default(_) => {
                // extract current cases and build an opposite expression
                if self.current_cases.is_empty() {
                    "any".to_string()
                } else {
                    format!("!({})", self.current_cases.join(" -or "))
                }
            }
            EnumExpressionPart::NoDefault(_) => "".to_string(),
        })
    }

    fn get_state_def<'src>(
        gc: &'src IR2,
        state_decl: &'src StateDeclaration,
    ) -> Result<&'src StateDef<'src>> {
        match gc.resources.get(&state_decl.resource) {
            Some(r) => match r.states.get(&state_decl.state) {
                Some(s) => Ok(s),
                None => Err(Error::new(format!(
                    "No method relies on the \"{}\" state for \"{}\"",
                    state_decl.state.fragment(),
                    state_decl.resource.fragment()
                ))),
            },
            None => {
                return Err(Error::new(format!(
                    "No method relies on the \"{}\" resource",
                    state_decl.resource.fragment()
                )))
            }
        }
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
        st: &Statement,
        condition_content: String,
    ) -> Result<Vec<Call>> {
        match st {
            Statement::StateDeclaration(sd) => {
                if let Some(var) = sd.outcome {
                    self.new_var(&var);
                }

                let method_name = &format!("{}-{}", sd.resource.fragment(), sd.state.fragment());
                let mut parameters = fetch_method_parameters(gc, sd, |name, value| {
                    Parameter::method_parameter(name, value)
                });
                let state_def = Self::get_state_def(gc, sd)?;
                let class_param_index = state_def.class_parameter_index(method_name)?;
                if parameters.len() < class_param_index {
                    return Err(Error::new(format!(
                        "Class param index is out of bounds for {}",
                        method_name
                    )));
                }
                // tmp -1, index of lib generator is off 1
                let class_param = parameters.remove(class_param_index);
                let is_dsc_supported = state_def
                    .supported_formats(method_name)?
                    .contains(&"dsc".to_owned());

                let component = match sd.metadata.get("component") {
                    Some(TomlValue::String(s)) => s.to_owned(),
                    _ => "any".to_string(),
                };

                Ok(Method::new()
                    .resource(sd.resource.fragment().to_string())
                    .state(sd.state.fragment().to_string())
                    .parameters(Parameters(parameters))
                    .class_parameter(class_param.clone())
                    .component(component)
                    .supported(is_dsc_supported)
                    .condition(self.format_condition(condition_content)?) // TODO
                    .source(sd.source.fragment())
                    .build())
            }
            Statement::Case(_case, vec) => {
                self.reset_cases();
                let mut res = vec![];

                for (case, vst) in vec {
                    let case_exp = self.format_case_expr(gc, &case.expression)?;
                    for st in vst {
                        res.append(&mut self.format_statement(gc, st, case_exp.clone())?);
                    }
                }
                Ok(res)
            }
            Statement::Fail(msg) => Ok(vec![Call::abort(
                Parameters::new()
                    .message("policy_fail")
                    .message(&self.value_to_string(msg, true)?), //self.parameter_to_dsc(msg, "Fail")?,
            )]),
            Statement::LogDebug(msg) => Ok(vec![Call::log(
                Parameters::new()
                    .message("log_debug")
                    .message(&self.value_to_string(msg, true)?)
                    //self.parameter_to_dsc(msg, "Log")?,
                    .message("None")
                    // TODO: unique class prefix
                    .message("log_debug"),
            )]),
            Statement::LogInfo(msg) => Ok(vec![Call::log(
                Parameters::new()
                    .message("log_info")
                    .message(&self.value_to_string(msg, true)?)
                    .message("None")
                    // TODO: unique class prefix
                    .message("log_info"),
            )]),
            Statement::LogWarn(msg) => Ok(vec![Call::log(
                Parameters::new()
                    .message("log_warn")
                    .message(&self.value_to_string(msg, true)?)
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
            _ => Ok(Vec::new()),
        }
    }

    fn value_to_string(&mut self, value: &Value, string_delim: bool) -> Result<String> {
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
                            s.replace("$", "${consr.dollar}")
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
            Value::EnumExpression(_e) => unimplemented!(),
            Value::List(l) => format!(
                "[ {} ]",
                map_strings_results(l.iter(), |x| self.value_to_string(x, true), ",")?
            ),
            Value::Struct(s) => format!(
                "{{ {} }}",
                map_strings_results(
                    s.iter(),
                    |(x, y)| Ok(format!(r#""{}":{}"#, x, self.value_to_string(y, true)?)),
                    ","
                )?
            ),
        })
    }

    fn format_condition(&mut self, condition: String) -> Result<Condition> {
        self.current_cases.push(condition.clone());
        Ok(match &self.return_condition {
            None => condition,
            Some(c) => format!("({}) -and ({})", c, condition),
        })
    }

    pub fn format_param_type(&self, type_: &Type) -> String {
        String::from(match type_ {
            Type::String => "String",
            Type::Float => "Double",
            Type::Integer => "Int",
            Type::Boolean => "Bool",
            Type::List => "Array", // actually needs implementation work
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
    ) -> Result<Vec<ActionResult>> {
        let mut files: Vec<ActionResult> = Vec::new();
        // TODO add global variable definitions
        for (resource_name, resource) in gc.resources.iter() {
            for (state_name, state) in resource.states.iter() {
                // This condition actually rejects every file that is not the input filename
                // therefore preventing from having an output in another directory
                // Solutions: check filename rather than path, or accept everything that is not from crate root lib
                if source_file != state_name.file() {
                    // means it's a lib file, not the file we want to generate
                    continue;
                }
                self.reset_context();

                // get parameters, default params are hardcoded for now
                let formatted_parameters: Vec<String> = resource
                    .parameters
                    .iter()
                    .chain(state.parameters.iter())
                    .map(|p| {
                        format!(
                            "[Parameter(Mandatory=$True)]  [{}] ${}",
                            &self.format_param_type(&p.type_),
                            pascebab_case(p.name.fragment())
                        )
                    })
                    .chain(vec![
                        "[Parameter(Mandatory=$False)] [Switch] $AuditOnly".to_owned(),
                        "[Parameter(Mandatory=$True)]  [String] $ReportId".to_owned(),
                        "[Parameter(Mandatory=$True)]  [String] $TechniqueName".to_owned(),
                    ])
                    .collect::<Vec<String>>();

                let fn_name = format!("{} {}", resource_name.fragment(), state_name.fragment(),);

                let mut function = Function::agent(fn_name.clone())
                    .parameters(formatted_parameters.clone())
                    // Standard variables for all techniques
                    .scope(vec![
                        Call::variable("$LocalClasses", "New-ClassContext"),
                        Call::variable("$ResourcesDir", "$PSScriptRoot + \"\\resources\""),
                    ]);

                for methods in state
                    .statements
                    .iter()
                    .flat_map(|statement| self.format_statement(gc, statement, "any".to_string()))
                {
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
                files.push(ActionResult::new(
                    Format::DSC,
                    dest_file.map(|o| PathBuf::from(o)),
                    Some(content),
                ));
            }
        }
        Ok(files)
    }
}
