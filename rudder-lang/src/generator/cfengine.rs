// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use super::Generator;
use crate::{
    command::CommandResult,
    error::*,
    generator::cfengine::syntax::{quoted, Bundle, Method, Policy, Promise, MAX_INT, MIN_INT},
    generator::Format,
    ir::{enums::EnumExpressionPart, ir2::IR2, resource::*, value::*},
    // generator::cfengine::syntax::{quoted, Bundle, Method, Policy, Promise},
    // ir::{enums::EnumExpressionPart, resource::*, value::*, *},
    parser::*,
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

    fn format_class(&mut self, class: Condition) -> Result<Condition> {
        self.current_cases.push(class.clone());
        Ok(match &self.return_condition {
            None => class,
            Some(c) => format!("({}).({})", c, class),
        })
    }

    fn format_case_expr(&mut self, gc: &IR2, case: &EnumExpressionPart) -> Result<Condition> {
        Ok(match case {
            EnumExpressionPart::And(e1, e2) => {
                let mut lexpr = self.format_case_expr(gc, e1)?;
                let mut rexpr = self.format_case_expr(gc, e2)?;
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
                self.format_case_expr(gc, e1)?,
                self.format_case_expr(gc, e2)?
            ),
            // TODO what about classes that have not yet been set? can it happen?
            EnumExpressionPart::Not(e1) => {
                let mut expr = self.format_case_expr(gc, e1)?;
                if expr.contains('|') || expr.contains('&') {
                    expr = format!("({})", expr);
                }
                format!("!{}", expr)
            }
            EnumExpressionPart::Compare(var, e, item) => {
                if let Some(true) = gc.enum_list.enum_is_global(*e) {
                    gc.enum_list.get_item_cfengine_name(*var, *item)
                } else {
                    // concat var name + item
                    let prefix = &self.var_prefixes[var.fragment()];
                    // TODO there may still be some conflicts with var or enum containing '_'
                    format!("{}_{}_{}", prefix, e.fragment(), item.fragment())
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
        })
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
        st: &Statement,
        in_class: String,
    ) -> Result<Vec<Promise>> {
        match st {
            Statement::StateDeclaration(sd) => {
                if let Some(var) = sd.outcome {
                    self.new_var(&var);
                }

                let component = match sd.metadata.get("component") {
                    Some(TomlValue::String(s)) => s.to_owned(),
                    // TODO what is the any component ?
                    _ => "any".to_string(),
                };

                // TODO setup mode and output var by calling ... bundle
                let parameters = sd
                    .resource_params
                    .iter()
                    .chain(sd.state_params.iter())
                    .map(|x| self.value_to_string(x, true))
                    .collect::<Result<Vec<String>>>()?;

                let state_param = sd
                    .resource_params
                    .get(0)
                    .and_then(|p| self.value_to_string(&p, false).ok())
                    .unwrap_or_else(|| "".to_string());

                Ok(Method::new()
                    .resource(sd.resource.fragment().to_string())
                    .state(sd.state.fragment().to_string())
                    .parameters(parameters)
                    .report_parameter(state_param)
                    .report_component(component)
                    .condition(self.format_class(in_class)?)
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
            Statement::Fail(msg) => Ok(vec![Promise::usebundle(
                "_abort",
                None,
                vec![quoted("policy_fail"), self.value_to_string(msg, true)?],
            )]),
            Statement::LogDebug(msg) => Ok(vec![Promise::usebundle(
                "log_rudder_mode",
                None,
                vec![
                    quoted("log_debug"),
                    self.value_to_string(msg, true)?,
                    quoted("None"),
                    // TODO: unique class prefix
                    quoted("log_debug"),
                ],
            )]),
            Statement::LogInfo(msg) => Ok(vec![Promise::usebundle(
                "log_rudder_mode",
                None,
                vec![
                    quoted("log_info"),
                    self.value_to_string(msg, true)?,
                    quoted("None"),
                    // TODO: unique class prefix
                    quoted("log_info"),
                ],
            )]),
            Statement::LogWarn(msg) => Ok(vec![Promise::usebundle(
                "log_rudder_mode",
                None,
                vec![
                    quoted("log_warn"),
                    self.value_to_string(msg, true)?,
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
                    Promise::usebundle("success", None, vec![])
                } else if *outcome == Token::new("", "repaired") {
                    Promise::usebundle("repaired", None, vec![])
                } else {
                    Promise::usebundle("error", None, vec![])
                }])
            }
            Statement::Noop => Ok(vec![]),
            // TODO Statement::VariableDefinition()
            _ => Ok(vec![]),
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
            Value::Integer(t, n) => {
                if *n >= MIN_INT && *n <= MAX_INT {
                    format!("{}", n)
                } else {
                    return Err(err!(t, "Integer overflow"));
                }
            }
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
                // This condition actually rejects every file that is not the input filename
                // therefore preventing from having an output in another directory
                // Solutions: check filename rather than path, or accept everything that is not from crate root lib
                if source_file != state_name.file() {
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

                for methods in state
                    .statements
                    .iter()
                    .flat_map(|statement| self.format_statement(gc, statement, "any".to_string()))
                {
                    bundle.add_promise_group(methods);
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
                        .unwrap_or_else(|| "unknown".to_string())
                };

                if policy_metadata {
                    let policy = Policy::new()
                        .name(extract("name"))
                        .version(extract("version"))
                        .description(extract("description"))
                        .bundle(bundle);
                    files.push(CommandResult::new(
                        Format::CFEngine,
                        dest_file.map(|o| PathBuf::from(o)),
                        Some(policy.to_string()),
                    ));
                } else {
                    files.push(CommandResult::new(
                        Format::CFEngine,
                        dest_file.map(|o| PathBuf::from(o)),
                        Some(bundle.to_string()),
                    ));
                }
            }
        }
        Ok(files)
    }
}
