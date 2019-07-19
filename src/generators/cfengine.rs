use crate::ast::*;
use crate::ast::enums::*;
use crate::ast::resource::*;
use crate::ast::value::*;
use crate::parser::*;
use super::Generator;

use std::collections::HashMap;
use std::fs::File;
use std::io::Write;
use std::path::Path;

use crate::error::*;

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
    pub fn new() -> CFEngine {
        CFEngine {
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

    fn parameter_to_cfengine(&mut self, param: &Value) -> Result<String> {
        Ok(match param {
            Value::String(s) =>
            // TODO variable reinterpret (rudlang systemvar to cfengine systemvar)
            {
                "\"".to_owned()
                    + s.format(
                        |x: &str| {
                            x.replace("\\", "\\\\") // backslash escape
                                .replace("\"", "\\\"") // quote escape
                                .replace("$", "${const.dollar}")
                        }, // dollar escape
                        |y: &str| "${".to_owned() + y + "}", // variable inclusion
                    )
                    .as_str()
                    + "\""
            }
            Value::Number(_,_) => unimplemented!(),
            Value::EnumExpression(e) => "".into(), // TODO
            Value::List(_) => unimplemented!(),
            Value::Struct(_) => unimplemented!(),
        })
    }

    fn format_class(&mut self, class: String) -> Result<String> {
        self.current_cases.push(class.clone());
        Ok(match &self.return_condition {
            None => class,
            Some(c) => format!("({}).({})", c, class),
        })
    }
    fn format_case_expr(&mut self, gc: &AST, case: &EnumExpression) -> Result<String> {
        Ok(match case {
            EnumExpression::And(e1, e2) => format!(
                "({}).({})",
                self.format_case_expr(gc, e1)?,
                self.format_case_expr(gc, e2)?
            ),
            EnumExpression::Or(e1, e2) => format!(
                "({})|({})",
                self.format_case_expr(gc, e1)?,
                self.format_case_expr(gc, e2)?
            ),
            // TODO what about classes that have not yet been set ? can it happen ?
            EnumExpression::Not(e1) => format!("!({})", self.format_case_expr(gc, e1)?),
            EnumExpression::Compare(var, e, item) => {
                if gc.global_context.enum_list.is_global(*e) {
                    let final_enum = gc.global_context.enum_list.find_descendant_enum(*e, *item);
                    if *e == final_enum {
                        item.fragment().to_string()
                    } else {
                        let others = gc
                            .global_context
                            .enum_list
                            .enum_iter(*e)
                            .filter(|i| {
                                (**i != *item)
                                    && gc
                                        .global_context
                                        .enum_list
                                        .is_ancestor(*e, **i, final_enum, *item)
                            })
                            .map(|i| i.fragment())
                            .collect::<Vec<_>>();
                        format!(
                            "{}.!({})",
                            item.fragment().to_string(),
                            (&others[..]).join("|")
                        )
                    }
                } else {
                    // concat var name + item
                    let prefix = &self.var_prefixes[var.fragment()];
                    // TODO there may still be some conflicts with var or enum containing '_'
                    format!("{}_{}_{}", prefix, e.fragment(), item.fragment())
                }
            }
            EnumExpression::Default(_) => {
                // extract current cases and build an opposite expression
                if self.current_cases.is_empty() {
                    "any".to_string()
                } else {
                    format!("!({})", self.current_cases.join("|"))
                }
            }
        })
    }

    // TODO simplify expression and remove useless conditions for more readable cfengine
    // TODO underscore escapement
    // TODO how does cfengine use utf8
    // TODO variables
    // TODO comments and metadata
    // TODO use in_class everywhere
    fn format_statement(&mut self, gc: &AST, st: &Statement, in_class: String) -> Result<String> {
        match st {
            Statement::StateCall(metadata, _mode, res, res_parameters, call, params, out) => {
                if let Some(var) = out {
                    self.new_var(var);
                }
                let component = match metadata.get(&"component".into()) {
                    // TODO use static_to_string
                    Some(Value::String(s)) => match &s.data[0] {
                        PInterpolatedElement::Static(st) => st.clone(),
                        _ => "any".to_string(),
                    },
                    _ => "any".to_string(),
                };
                // TODO setup mode and output var by calling ... bundle
                let param_str = map_strings_results(res_parameters
                    .iter()
                    .chain(params.iter()),
                    |x| self.parameter_to_cfengine(x),
                    ","
                )?;
                Ok(format!(
                    "      \"{}\" usebundle => {}_{}({}), if=>{};\n",
                    component,
                    res.fragment(),
                    call.fragment(),
                    param_str,
                    self.format_class(in_class)?
                ))
            }
            Statement::Case(_case, vec) => {
                self.reset_cases();
                map_strings_results(vec
                    .iter(),
                    |(case, vst)| {
                        // TODO case in case
                        let case_exp = self.format_case_expr(gc, case)?;
                        map_strings_results(
                            vst.iter(),
                            |st| self.format_statement(gc, st, case_exp.clone()),
                            ""
                        )
                    },
                    ""
                )
            }
            Statement::Fail(msg) => Ok(format!(
                "      \"method_call\" usebundle => ncf_fail({});\n",
                self.parameter_to_cfengine(msg)?
            )),
            Statement::Log(msg) => Ok(format!(
                "      \"method_call\" usebundle => ncf_log({});\n",
                self.parameter_to_cfengine(msg)?
            )),
            Statement::Return(outcome) => {
                // handle end of bundle
                self.return_condition = Some(match self.current_cases.last() {
                    None => "!any".into(),
                    Some(c) => format!("!({})", c),
                });
                Ok(if *outcome == Token::new("", "kept") {
                    "      \"method_call\" usebundle => success();\n".into()
                } else if *outcome == Token::new("", "repaired") {
                    "      \"method_call\" usebundle => repaired();\n".into()
                } else {
                    "      \"method_call\" usebundle => error();\n".into()
                })
            }
            Statement::Noop => Ok(String::new()),
            // TODO Statement::VariableDefinition()
            _ => Ok(String::new()),
        }
    }

    fn value_to_string(&mut self, value: &Value) -> Result<String> {
        Ok(match value {
            Value::String(s) => format!("\"{}\"", s.data.iter().map(|t| 
                match t {
                    PInterpolatedElement::Static(s) => {
                        // replace ${const.xx}
                        s.replace("$","${consr.dollar}")
                         .replace("\\n","${const.n}")
                         .replace("\\r","${const.r}")
                         .replace("\\t","${const.t}")
                    }
                    PInterpolatedElement::Variable(v) => {
                        // translate variable name
                        format!("${{{}}}",v)
                    }
                }).collect::<Vec<String>>().join("")),
            Value::Number(_, n) => format!("{}",n),
            Value::EnumExpression(e) => unimplemented!(),
            Value::List(l) => format!("[ {} ]", map_strings_results(l.iter(), |x| self.value_to_string(x), ",")?),
            Value::Struct(s) => format!("{{ {} }}", map_strings_results(s.iter(), |(x,y)| Ok(format!("\"{}\":{}",x,self.value_to_string(y)?)), ",")?),
        })
    }

    fn generate_ncf_metadata(&mut self, name: &Token, resource: &ResourceDef) -> Result<String> {
        map_strings_results(resource.metadata.iter(),
            |(n,v)| Ok(format!("# @{} {}", n.fragment(), self.value_to_string(v)?)),
            "\n"
        )
    }
}

impl Generator for CFEngine {
    // TODO methods differ if this is a technique generation or not
    fn generate(&mut self, gc: &AST, file: Option<&Path>, technique_metadata: bool) -> Result<()> {
        let mut files: HashMap<&str, String> = HashMap::new();
        // TODO add global variable definitions
        for (rn, res) in gc.resources.iter() {
            for (sn, state) in res.states.iter() {
                if let Some(file_name) = file {
                    if file_name.to_string_lossy() != sn.file() {
                        continue;
                    }
                }
                self.reset_context();
                let mut content = match files.get(sn.file()) {
                    Some(s) => s.to_string(),
                    None => if technique_metadata {
                        self.generate_ncf_metadata(rn,res)?
                    } else {
                        String::new()
                    },
                };
                let params = res
                    .parameters
                    .iter()
                    .chain(state.parameters.iter())
                    .map(|p| p.name.fragment())
                    .collect::<Vec<&str>>()
                    .join(",");
                content.push_str(&format!(
                    "bundle agent {}_{} ({})\n",
                    rn.fragment(),
                    sn.fragment(),
                    params
                ));
                content.push_str("{\n  methods:\n");
                for st in state.statements.iter() {
                    content.push_str(&self.format_statement(gc, st, "any".to_string())?);
                }
                content.push_str("}\n");
                files.insert(sn.file(), content);
            }
        }
        for (name, content) in files.iter() {
            let mut file = File::create(format!("{}.cf", name)).unwrap();
            file.write_all(content.as_bytes()).unwrap();
        }
        Ok(())
    }
}
