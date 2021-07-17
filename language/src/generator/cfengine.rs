// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use super::Generator;
use crate::{
    command::CommandResult,
    error::*,
    generator::cfengine::syntax::{quoted, Bundle, Method, Policy, Promise, MAX_INT, MIN_INT},
    generator::Format,
    ir::{enums::EnumExpressionPart, ir2::IR2, resource::*, value::*, variable::VariableDef},
    parser::*,
    // generator::cfengine::syntax::{quoted, Bundle, Method, Policy, Promise},
    // ir::{enums::EnumExpressionPart, resource::*, value::*, *},
    technique::fetch_method_parameters,
};
use std::{
    collections::HashMap,
    path::{Path, PathBuf},
};
use toml::Value as TomlValue;

mod syntax;

type Condition = String;

pub struct CFEngine {
    // list of already formatted expression in current case
    current_cases: Vec<String>,
    // match enum local variables with class prefixes
    var_prefixes: HashMap<String, String>,
    // already used class prefix
    prefixes: HashMap<String, u32>,
    // condition to add for every other condition for early return
    return_condition: Option<String>,
}

impl CFEngine {
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

    fn format_class(&mut self, class: Condition) -> Condition {
        self.current_cases.push(class.clone());
        match &self.return_condition {
            None => class,
            Some(c) => format!("({}).({})", c, class),
        }
    }

    fn format_case_expr(
        &mut self,
        gc: &IR2,
        case: &EnumExpressionPart,
        parentCondition: Option<Condition>,
    ) -> Result<Condition> {
        let expr = match case {
            EnumExpressionPart::And(e1, e2) => {
                let mut lexpr = self.format_case_expr(gc, e1, None)?;
                let mut rexpr = self.format_case_expr(gc, e2, None)?;
                if lexpr.contains('|') {
                    lexpr = format!("({})", lexpr);
                }
                if rexpr.contains('|') {
                    rexpr = format!("({})", rexpr);
                }
                format!("{}.{}", lexpr, rexpr)
            }
            EnumExpressionPart::Or(e1, e2) => format!(
                "{}|{}",
                self.format_case_expr(gc, e1, None)?,
                self.format_case_expr(gc, e2, None)?
            ),
            // TODO what about classes that have not yet been set? can it happen?
            EnumExpressionPart::Not(e1) => {
                let mut expr = self.format_case_expr(gc, e1, None)?;
                if expr.contains('|') || expr.contains('&') {
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
                        // value is known so concat var name + item
                        // let prefix = &self.var_prefixes[var.fragment()];
                        // // TODO there may still be some conflicts with var or enum containing '_'
                        // format!("{}_{}_{}", var.fragment(), e.fragment(), item.fragment())
                        format!(
                            r#"{}_",canonify("${{report_data.canonified_directive_id}}"),"_{}"#,
                            var.fragment(),
                            item.fragment()
                        )
                    }
                }
            }
            EnumExpressionPart::RangeCompare(_var, _e, _item1, _item2) => unimplemented!(), // TODO
            EnumExpressionPart::Default(_) => {
                // extract current cases and build an opposite expression
                if self.current_cases.is_empty() {
                    "any".to_string()
                } else {
                    format!("!({})", self.current_cases.join("|"))
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

    // TODO simplify expression and remove useless conditions for more readable cfengine
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
        condition: Option<Condition>,
    ) -> Result<Vec<Promise>> {
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
                let inner_state_def = gc.get_state_def(&var.resource, &var.state)?;
                let method_name = &format!("{}_{}", var.resource.fragment(), var.state.fragment());

                let component = match var
                    .metadata
                    .get("component")
                    .or(inner_state_def.metadata.get("name"))
                {
                    Some(TomlValue::String(s)) => s.to_owned(),
                    _ => method_name.to_owned(),
                };

                // TODO setup mode and output var by calling ... bundle
                let parameters =
                    fetch_method_parameters(gc, &var.to_method(), |_name, value, _metadatas| {
                        self.value_to_string(value, &variables, true)
                    })
                    .into_iter()
                    .collect::<Result<Vec<String>>>()?;

                let is_cf_supported = match inner_state_def.supported_targets(method_name) {
                    Ok(targets) => targets.contains(&"cf".to_owned()),
                    Err(_) => true,
                };
                let class_param_index = inner_state_def.class_parameter_index(method_name)?;
                let class_param = var
                    .resource_params
                    .get(class_param_index)
                    .and_then(|p| self.value_to_string(&p, &variables, false).ok())
                    .unwrap_or_else(|| "".to_string());
                let id = var
                    .metadata
                    .get("id")
                    .and_then(|v| v.as_str())
                    .map(String::from);

                let method = Method::new()
                    .resource(var.resource.fragment().to_string())
                    .state(var.state.fragment().to_string())
                    .parameters(parameters)
                    .report_component(component)
                    .report_parameter(class_param)
                    .condition(
                        condition.map_or_else(|| String::from("any"), |x| self.format_class(x)),
                    )
                    .id(id.unwrap_or(String::new()))
                    .supported(is_cf_supported);
                Ok(method.build())
            }
            Statement::StateDeclaration(sd) => {
                let inner_state_def = gc.get_state_def(&sd.resource, &sd.state)?;
                let method_name = &format!("{}_{}", sd.resource.fragment(), sd.state.fragment());

                if let Some(var) = sd.outcome {
                    self.new_var(&var);
                }

                let component = match sd
                    .metadata
                    .get("component")
                    .or(inner_state_def.metadata.get("name"))
                {
                    Some(TomlValue::String(s)) => s.to_owned(),
                    _ => method_name.to_owned(),
                };

                // TODO setup mode and output var by calling ... bundle
                let parameters = sd
                    .resource_params
                    .iter()
                    .chain(sd.state_params.iter())
                    .map(|x| self.value_to_string(x, &variables, true))
                    .collect::<Result<Vec<String>>>()?;

                let is_cf_supported = match inner_state_def.supported_targets(method_name) {
                    Ok(targets) => targets.contains(&"cf".to_owned()),
                    Err(_) => true,
                };
                let class_param = match sd
                    .resource_params
                    .get(inner_state_def.class_parameter_index(method_name)?)
                    .and_then(|p| self.value_to_string(&p, &variables, false).ok())
                {
                    Some(s) => s,
                    None => return Err(Error::new("Expected a component metadata".to_owned())),
                };

                let alias = sd
                    .metadata
                    .get("method_alias")
                    .and_then(|v| v.as_str())
                    .map(String::from);
                let id = sd
                    .metadata
                    .get("id")
                    .and_then(|v| v.as_str())
                    .map(String::from)
                    .unwrap_or("".to_string());

                let method = Method::new()
                    .resource(sd.resource.fragment().to_string())
                    .state(sd.state.fragment().to_string())
                    .alias(alias)
                    .parameters(parameters)
                    .report_parameter(class_param)
                    .report_component(component)
                    .supported(is_cf_supported)
                    .condition(
                        condition.map_or_else(|| String::from("any"), |x| self.format_class(x)),
                    )
                    .source(sd.source.fragment())
                    .id(id);

                Ok(method.build())
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
                        Some(case_exp.clone()),
                    )?);
                }
                Ok(res)
            }
            Statement::Fail(msg) => Ok(vec![Promise::usebundle(
                "_abort",
                None,
                None,
                vec![
                    quoted("policy_fail"),
                    self.value_to_string(msg, &variables, true)?,
                ],
            )]),
            Statement::LogDebug(msg) => Ok(vec![Promise::usebundle(
                "log_rudder_mode",
                None,
                None,
                vec![
                    quoted("log_debug"),
                    self.value_to_string(msg, &variables, true)?,
                    quoted("None"),
                    // TODO: unique class prefix
                    quoted("log_debug"),
                ],
            )]),
            Statement::LogInfo(msg) => Ok(vec![Promise::usebundle(
                "log_rudder_mode",
                None,
                None,
                vec![
                    quoted("log_info"),
                    self.value_to_string(msg, &variables, true)?,
                    quoted("None"),
                    // TODO: unique class prefix
                    quoted("log_info"),
                ],
            )]),
            Statement::LogWarn(msg) => Ok(vec![Promise::usebundle(
                "log_rudder_mode",
                None,
                None,
                vec![
                    quoted("log_warn"),
                    self.value_to_string(msg, &variables, true)?,
                    quoted("None"),
                    // TODO: unique class prefix
                    quoted("log_warn"),
                ],
            )]),
            Statement::Return(outcome) => {
                // handle end of bundle
                self.return_condition = Some(match self.current_cases.last() {
                    None => "!any".into(),
                    Some(c) => format!("!({})", c),
                });
                Ok(vec![if *outcome == Token::new("", "kept") {
                    Promise::usebundle("success", None, None, vec![])
                } else if *outcome == Token::new("", "repaired") {
                    Promise::usebundle("repaired", None, None, vec![])
                } else {
                    Promise::usebundle("error", None, None, vec![])
                }])
            }
            Statement::Noop => Ok(vec![]),
            // TODO Statement::VariableDefinition()
            Statement::VariableDefinition(_) => Ok(vec![]),
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
            Value::Float(_, n) => format!("{}{}{}", delim, n, delim),
            Value::Integer(t, n) => {
                if *n >= MIN_INT && *n <= MAX_INT {
                    format!("{}{}{}", delim, n, delim)
                } else {
                    return Err(err!(t, "Integer overflow"));
                }
            }
            Value::Boolean(_, b) => format!("{}{}{}", delim, b, delim),
            Value::EnumExpression(_e) => unimplemented!(),
            Value::List(l) => format!(
                "[ {} ]",
                map_strings_results(l.iter(), |x| self.value_to_string(x, variables, true), ",")?
            ),
            Value::Struct(s) => format!(
                "{{ {} }}",
                map_strings_results(
                    s.iter(),
                    |(x, y)| Ok(format!(
                        r#"{}{}{}:{}"#,
                        delim,
                        x,
                        delim,
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
                    "The variable {} isn't recognized by rudderc, so we can't guarantee it will be defined when evaluated",
                    v.fragment()
                );
                format!("{}${{{}}}{}", delim, v.fragment(), delim)
            }
        })
    }
}

impl Generator for CFEngine {
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
                if state.is_dependency {
                    // means it's a lib file, not the file we are interested to generate
                    continue;
                }

                self.reset_context();

                // Result bundle
                let bundle_name = format!("{}_{}", resource_name.fragment(), state_name.fragment());
                let parameters = resource
                    .parameters
                    .iter()
                    .chain(state.parameters.iter())
                    .map(|p| p.name.fragment().to_string())
                    .collect::<Vec<String>>();
                let mut bundle = Bundle::agent(bundle_name.clone())
                    .parameters(parameters.clone())
                    // Standard variables for all techniques
                    .promise_group(vec![
                        Promise::string("resources_dir", "${this.promise_dirname}/resources"),
                        Promise::slist("args", parameters.clone()),
                        Promise::string_raw("report_param", "join(\"_\", args)"),
                        Promise::string_raw(
                            "full_class_prefix",
                            format!("canonify(\"{}_${{report_param}}\")", &bundle_name),
                        ),
                        Promise::string_raw(
                            "class_prefix",
                            "string_head(\"${full_class_prefix}\", \"1000\")",
                        ),
                    ]);

                for res in state
                    .statements
                    .iter()
                    .map(|statement| self.format_statement(gc, resource, state, statement, None))
                {
                    match res {
                        Ok(methods) => bundle.add_promise_group(methods),
                        Err(e) => return Err(e),
                    }
                }

                let extract = |name: &str| {
                    resource
                        .metadata
                        .get(name)
                        .and_then(|v| match v {
                            TomlValue::Integer(n) => Some(n.to_string()),
                            TomlValue::String(s) => Some(s.to_owned()),
                            _ => None,
                        })
                        .unwrap_or_else(|| "unknown".to_string()) // ERROR in this case
                };

                if policy_metadata {
                    let policy = Policy::new()
                        .name(extract("name"))
                        .version(extract("version"))
                        .description(extract("description"))
                        // add parameters
                        .bundle(bundle);
                    files.push(CommandResult::new(
                        Format::CFEngine,
                        dest_file.map(PathBuf::from),
                        Some(policy.to_string()),
                    ));
                } else {
                    files.push(CommandResult::new(
                        Format::CFEngine,
                        dest_file.map(PathBuf::from),
                        Some(bundle.to_string()),
                    ));
                }
            }
        }
        Ok(files)
    }
}
