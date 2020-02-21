// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use super::Generator;
use crate::ast::enums::*;
use crate::ast::resource::*;
use crate::ast::value::*;
use crate::ast::*;
use crate::parser::*;

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
            Value::Number(_, _) => unimplemented!(),
            Value::Boolean(_, _) => unimplemented!(),
            Value::EnumExpression(_e) => "".into(), // TODO
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
                if gc.enum_list.is_global(*e) {
                    let final_enum = gc.enum_list.find_descendant_enum(*e, *item);
                    if *e == final_enum {
                        item.fragment().to_string() // here
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
        })
    }

    fn get_config_from_file() -> Result<toml::Value> {
        let config_filename = "libs/translate_config.toml";
        let file_error = |filename: &str, err| err!(Token::new(&filename.to_owned(), ""), "{}", err);
        let config_data =
            std::fs::read_to_string(config_filename).map_err(|e| file_error(config_filename, e))?;
        toml::from_str(&config_data).map_err(|e| err!(Token::new(config_filename, ""), "{}", e))
    }
    fn get_class_parameter_index(method_name: String) -> Result<usize> {
        // outcome detection and formating
        let config = Self::get_config_from_file()?;
        let mconf = match config.get("methods") {
            None => return Err(Error::User("No methods section in config.toml".into())),
            Some(m) => m,
        };
        let method = match mconf.get(&method_name) {
            None => {
                return Err(Error::User(format!(
                    "Unknown generic method call: {}",
                    &method_name
                )))
            }
            Some(m) => m,
        };
        match method.get("class_parameter_id") {
            None => {
                Err(Error::User(format!(
                    "Undefined class_parameter_id for {}",
                    &method_name
                )))
            }
            Some(m) => Ok(m.as_integer().unwrap() as usize)
        }
    }

    // TODO simplify expression and remove useless conditions for more readable cfengine
    // TODO underscore escapement
    // TODO how does cfengine use utf8
    // TODO variables
    // TODO comments and metadata
    // TODO use in_class everywhere
    fn format_statement(
        &mut self,
        gc: &AST,
        st: &Statement,
        id: usize,
        in_class: String,
    ) -> Result<String> {
        match st {
            Statement::StateDeclaration(sd) => {
                if let Some(var) = sd.outcome {
                    self.new_var(&var);
                }
                let component = match sd.metadata.get(&"component".into()) {
                    // TODO use static_to_string
                    Some(Value::String(s)) => match &s.data[0] {
                        PInterpolatedElement::Static(st) => st.clone(),
                        _ => "any".to_string(),
                    },
                    _ => "any".to_string(),
                };
                // TODO setup mode and output var by calling ... bundle
                let param_str = map_strings_results(
                    sd.resource_params.iter().chain(sd.state_params.iter()),
                    |x| self.parameter_to_cfengine(x),
                    ", ",
                )?;
                let method_name = format!("{}_{}", sd.resource.fragment(), sd.state.fragment());
                let index = Self::get_class_parameter_index(method_name)?;
                let class = self.format_class(in_class)?;
                let resource_param = if sd.resource_params.len() > index {
                    if let Ok(param) = self.parameter_to_cfengine(&sd.resource_params[index]) {
                        format!(", {}", param)
                    } else {
                        "".to_string()
                    }
                } else {
                    "".to_string()
                };
                let method_reporting_context = &format!(
                    "    \"{}_${{report_data.directive_id}}_{}\" usebundle => _method_reporting_context(\"{}\"{})",
                    component,
                    id,
                    component,
                    resource_param,
                );
                let method = &format!(
                    "    \"{}_${{report_data.directive_id}}_{}\" usebundle => {}_{}({})",
                    component,
                    id,
                    sd.resource.fragment(),
                    sd.state.fragment(),
                    param_str,
                );
                // facultative, only aesthetic to align the method `=>` with the conditonal `=>` like cfengine does
                let padding_spaces = str::repeat(" ", component.len() + id.to_string().len() + 43);
                if class == "any" {
                    Ok(format!("{};\n{};\n", method_reporting_context, method))
                } else {
                    let condition = &format!("{}if => concat(\"{}\");", padding_spaces, class);
                    Ok(format!(
                        "{},\n{}\n{},\n{}\n",
                        method_reporting_context,
                        condition,
                        method,
                        condition
                    ))
                }
            }
            Statement::Case(_case, vec) => {
                self.reset_cases();
                map_strings_results(
                    vec.iter(),
                    |(case, vst)| {
                        // TODO case in case
                        let case_exp = self.format_case_expr(gc, case)?;
                        map_strings_results(
                            vst.iter(),
                            |st| self.format_statement(gc, st, id, case_exp.clone()),
                            "",
                        )
                    },
                    "",
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
                    .join(""),
                delim
            ),
            Value::Number(_, n) => format!("{}", n),
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

    fn generate_parameters_metadatas<'src>(&mut self, parameters: Option<Value<'src>>) -> String {
        let mut params_str = String::new();

        let mut get_param_field = |param: &Value, entry: &str| -> String {
            if let Value::Struct(param) = &param {
                if let Some(val) = param.get(entry) {
                    if let Ok(val_s) = self.value_to_string(val, false) {
                        return match val {
                            Value::String(_) => format!("{:?}: {:?}", entry, val_s),
                            _ => format!("{:?}: {}", entry, val_s)
                        }
                    }
                }
            }
            "".to_owned()
        };

        if let Some(Value::List(parameters)) = parameters {
            parameters.iter().for_each(|param| {
                params_str.push_str(&format!(
                    "# @parameter {{ {}, {}, {} }}\n",
                    get_param_field(param, "name"),
                    get_param_field(param, "id"),
                    get_param_field(param, "constraints")
                ));
            });
        };
        params_str
    }

    fn generate_ncf_metadata(&mut self, _name: &Token, resource: &ResourceDef) -> Result<String> {
        let mut meta = resource.metadata.clone();
        // removes parameters from meta and returns it formatted
        let parameters: String = self.generate_parameters_metadatas(meta.remove(&Token::from("parameters")));
        // description must be the last field
        let mut map = map_hashmap_results(meta.iter(), |(n, v)| {
            Ok((n.fragment(), self.value_to_string(v, false)?))
        })?;
        let mut metadatas = String::new();
        let mut push_metadata = |entry: &str| {
            if let Some(val) = map.remove(entry) {
                metadatas.push_str(&format!("# @{} {}\n", entry, val));
            }
        };
        push_metadata("name");
        push_metadata("description");
        push_metadata("version");
        metadatas.push_str(&parameters);
        for (key, val) in map.iter() {
            metadatas.push_str(&format!("# @{} {}\n", key, val));
        }
        metadatas.push('\n');
        Ok(metadatas)
    }
}

impl Generator for CFEngine {
    // TODO methods differ if this is a technique generation or not
    fn generate(&mut self, gc: &AST, input_file: Option<&Path>, output_file: Option<&Path>, technique_metadata: bool) -> Result<()> {
        let mut files: HashMap<&str, String> = HashMap::new();
        // TODO add global variable definitions
        for (rn, res) in gc.resources.iter() {
            for (sn, state) in res.states.iter() {
                // This condition actually rejects every file that is not the input filename
                // therefore preventing from having an output in another directory
                // Solutions: check filename rather than path, or accept everything that is not from crate root lib
                let file_to_create = match input_file {
                    Some(filepath) => {
                        if filepath != Path::new(sn.file()) {
                            continue;
                        }
                        // can unwrap here since if input_file is Some, so does output_file (see end of compile.rs)
                        match output_file.unwrap().to_str() {
                            Some(output_filename) => output_filename,
                            None => sn.file(),
                        }
                    }
                    None => sn.file(),
                };
                self.reset_context();
                let mut content = "# generated by rudder-lang\n".to_owned();
                let fileinfo = match files.get(file_to_create) {
                    Some(s) => s.to_string(),
                    None => {
                        if technique_metadata {
                            self.generate_ncf_metadata(rn, res)?
                        } else {
                            String::new()
                        }
                    }
                };
                content.push_str(&fileinfo);
                let mut params = res
                    .parameters
                    .iter()
                    .chain(state.parameters.iter())
                    .map(|p| p.name.fragment())
                    .collect::<Vec<&str>>()
                    .join(",");
                if params.len() > 0 {
                    params = format!("({})", params);
                }
                content.push_str(&format!(
                    "bundle agent {}_{}{}\n",
                    rn.fragment(),
                    sn.fragment(),
                    params
                ));
                content.push_str(
                    "{\n  vars:\n    \"resources_dir\" string => \"${this.promise_dirname}/resources\";\n"
                );
                content.push_str("  methods:\n");
                for (i, st) in state.statements.iter().enumerate() {
                    content.push_str(&self.format_statement(gc, st, i, "any".to_string())?);
                }
                content.push_str("}\n");
                files.insert(file_to_create, content);
            }
        }
        if files.len() == 0 {
            match output_file {
                Some(filename) => File::create(filename).expect("Could not create output file"),
                None => return Err(Error::User("No file to create".to_owned()))
            };
        }
        for (name, content) in files.iter() {
            let mut file =
                File::create(name).expect("Could not create output file");
            file.write_all(content.as_bytes())
                .expect("Could not write content into output file");
        }
        Ok(())
    }
}
