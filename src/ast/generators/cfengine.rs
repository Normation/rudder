use super::super::*;
use super::Generator;

use std::collections::HashMap;
use std::fs::File;
use std::io::Write;

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
        self.current_cases = Vec::new();
    }
    fn reset_context(&mut self) {
        self.var_prefixes = HashMap::new();
        self.return_condition = None;
    }

    fn parameter_to_cfengine(&mut self, param: &Value) -> String {
        match param {
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
        }
    }

    fn format_class(&mut self, class: &str) -> String {
        let result = match &self.return_condition {
            None => format!("    {}::\n", class),
            Some(c) => format!("    ({}).({})::\n", c, class),
        };
        self.current_cases.push(class.into());
        result
    }
    fn format_case(&mut self, gc: &AST, case: &EnumExpression) -> String {
        let expr = self.format_case_expr(gc, case);
        self.format_class(&expr)
    }
    fn format_case_expr(&mut self, gc: &AST, case: &EnumExpression) -> String {
        match case {
            EnumExpression::And(e1, e2) => format!(
                "({}).({})",
                self.format_case_expr(gc, e1),
                self.format_case_expr(gc, e2)
            ),
            EnumExpression::Or(e1, e2) => format!(
                "({})|({})",
                self.format_case_expr(gc, e1),
                self.format_case_expr(gc, e2)
            ),
            // TODO what about classes that have not yet been set ? can it happen ?
            EnumExpression::Not(e1) => format!("!({})", self.format_case_expr(gc, e1)),
            EnumExpression::Compare(var, e, item) => {
                if gc.enum_list.is_global(*e) {
                    let final_enum = gc.enum_list.find_descendant_enum(*e, *item);
                    if *e == final_enum {
                        item.fragment().to_string()
                    } else {
                        let others = gc
                            .enum_list
                            .enum_iter(*e)
                            .filter(|i| {
                                (**i != *item)
                                    && gc.enum_list.is_ancestor(*e, **i, final_enum, *item)
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
        }
    }

    // TODO simplify expression and remove useless conditions for more readable cfengine
    // TODO underscore escapement
    // TODO how does cfengine use utf8
    // TODO variables
    // TODO comments and metadata
    fn format_statement(&mut self, gc: &AST, st: &Statement) -> String {
        match st {
            Statement::StateCall(_mode, res, res_parameters, call, params, out) => {
                if let Some(var) = out {
                    self.new_var(var);
                }
                // TODO setup mode and output var by calling ... bundle
                let param_str = res_parameters
                    .iter()
                    .chain(params.iter())
                    .map(|x| self.parameter_to_cfengine(x))
                    .collect::<Vec<String>>()
                    .join(",");
                format!(
                    "      \"method_call\" usebundle => {}_{}({});\n",
                    res.fragment(),
                    call.fragment(),
                    param_str
                )
            }
            Statement::Case(_case, vec) => {
                self.reset_cases();
                let mut lines = vec
                    .iter()
                    .map(|(case, vst)| {
                        format!(
                            "{}{}",
                            self.format_case(gc, case),
                            vst.iter()
                                .map(|st| self.format_statement(gc, st))
                                .collect::<Vec<String>>()
                                .join("")
                        )
                    })
                    .collect::<Vec<String>>();
                lines.push(self.format_class("any"));
                lines.join("")
            }
            Statement::Fail(msg) => format!(
                "      \"method_call\" usebundle => ncf_fail({});\n",
                self.parameter_to_cfengine(msg)
            ),
            Statement::Log(msg) => format!(
                "      \"method_call\" usebundle => ncf_log({});\n",
                self.parameter_to_cfengine(msg)
            ),
            Statement::Return(outcome) => {
                // handle end of bundle
                self.return_condition = Some(match self.current_cases.last() {
                    None => "!any".into(),
                    Some(c) => format!("!({})", c),
                });
                if *outcome == Token::new("", "kept") {
                    "      \"method_call\" usebundle => success();\n".into()
                } else if *outcome == Token::new("", "repaired") {
                    "      \"method_call\" usebundle => repaired();\n".into()
                } else {
                    "      \"method_call\" usebundle => error();\n".into()
                }
            }
            Statement::Noop => String::new(),
            _ => String::new(),
        }
    }
}

impl Generator for CFEngine {
    fn generate(&mut self, gc: &AST, file: Option<&str>) -> Result<()> {
        let mut files: HashMap<&str, String> = HashMap::new();
        for (rn, res) in gc.resources.iter() {
            for (sn, state) in res.states.iter() {
                if let Some(file_name) = file {
                    if file_name != sn.file() {
                        continue;
                    }
                }
                self.reset_context();
                let mut content = match files.get(sn.file()) {
                    Some(s) => s.to_string(),
                    None => String::new(),
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
                    content.push_str(&self.format_statement(gc, st));
                }
                content.push_str("}\n");
                files.insert(sn.file(), content.to_string()); // TODO there is something smelly with this to_string
            }
        }
        for (name, content) in files.iter() {
            let mut file = File::create(format!("{}.cf", name)).unwrap();
            file.write_all(content.as_bytes()).unwrap();
        }
        Ok(())
    }
}
