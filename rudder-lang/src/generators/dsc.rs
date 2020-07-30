// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use super::Generator;
use crate::{
    ast::{enums::EnumExpressionPart, resource::*, value::*, *},
    parser::*,
};

use std::{collections::HashMap, ffi::OsStr, fs::File, io::Write, path::Path};
use toml::Value as TomlValue;

use crate::error::*;

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
            Value::Number(_, _) => unimplemented!(),
            Value::Boolean(_, _) => unimplemented!(),
            Value::EnumExpression(_e) => "".into(), // TODO
            Value::List(_) => unimplemented!(),
            Value::Struct(_) => unimplemented!(),
        })
    }

    fn format_case_expr(&mut self, gc: &AST, case: &EnumExpressionPart) -> Result<String> {
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
                    println!("some: {}", item.fragment());
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

    fn get_class_parameter(
        &self,
        state_def: &StateDef,
        generic_method_name: &str,
    ) -> Result<(String, usize)> {
        // if one day several class_parameters are used as they previously were, get the relative code from this commit
        // 89651a6a8a05475eabccefc23e4fe23235a7e011 . This file, line 170
        match state_def.metadata.get("class_parameter") {
            Some(TomlValue::Table(parameters)) if parameters.len() == 1 => {
                let p = parameters.iter().next().unwrap();
                let p_index = match p.1 {
                    TomlValue::Integer(n) => *n as usize,
                    _ => {
                        return Err(Error::new(
                            "Expected value type for class parameters metadata: Number".to_owned(),
                        ))
                    }
                };
                Ok((p.0.to_owned(), p_index))
            },
            _ => Err(Error::new(format!(
                "Generic methods should have 1 class parameter (not the case for {})",
                generic_method_name
            )))
        }
    }

    fn get_method_parameters(
        &mut self,
        gc: &AST,
        state_decl: &StateDeclaration,
    ) -> Result<(String, String, bool)> {
        // depending on whether class_parameters should only be used for meta_generic_methods or not
        // might better handle relative errors as panic! rather than Error::User

        let state_def = match gc.resources.get(&state_decl.resource) {
            Some(r) => match r.states.get(&state_decl.state) {
                Some(s) => s,
                None => panic!(
                    "No method relies on the \"{}\" state for \"{}\"",
                    state_decl.state.fragment(),
                    state_decl.resource.fragment()
                ),
            },
            None => panic!(
                "No method relies on the \"{}\" resource",
                state_decl.resource.fragment()
            ),
        };

        let generic_method_name = &format!(
            "{}_{}",
            state_decl.state.fragment(),
            state_decl.resource.fragment()
        );

        let mut param_names = state_def
            .parameters
            .iter()
            .map(|p| p.name.fragment())
            .collect::<Vec<&str>>();

        let is_dsc_supported: bool = match state_def.metadata.get("supported_formats")
        {
            Some(TomlValue::Array(parameters)) => {
                let mut is_dsc_listed = false;
                for p in parameters {
                    if let TomlValue::String(s) = p {
                        if s == "dsc" {
                            is_dsc_listed = true;
                            break;
                        }
                    } else {
                        return Err(Error::new(
                            "Expected value type for supported_formats metadata: String".to_owned(),
                        ))
                    }
                }
                is_dsc_listed
            }
            _ => {
                return Err(Error::new(format!(
                    "{} Generic method has no \"supported_formats\" metadata",
                    generic_method_name
                )))
            }
        };

        let class_param = self.get_class_parameter(state_def, generic_method_name)?;

        param_names.insert(class_param.1, &class_param.0);

        // TODO setup mode and output var by calling ... bundle
        let params_string = map_strings_results(
            state_decl
                .resource_params
                .iter()
                .chain(state_decl.state_params.iter())
                .enumerate(),
            |(i, x)| {
                self.parameter_to_dsc(
                    x,
                    param_names.get(i).unwrap_or_else(|| {
                        panic!("Wrong parameter count for method {}", generic_method_name)
                    }),
                )
            },
            " ",
        )?;
        Ok((params_string, class_param.0, is_dsc_supported))
    }

    // TODO simplify expression and remove uselesmap_strings_results conditions for more readable cfengine
    // TODO underscore escapement
    // TODO how does cfengine use utf8
    // TODO variables
    // TODO comments and metadata
    // TODO use in_class everywhere
    fn format_statement(
        &mut self,
        gc: &AST,
        st: &Statement,
        condition_content: String,
    ) -> Result<String> {
        match st {
            Statement::StateDeclaration(sd) => {
                if let Some(var) = sd.outcome {
                    self.new_var(&var);
                }
                let component = match sd.metadata.get("component") {
                    // TODO use static_to_string
                    Some(TomlValue::String(s)) => s.to_owned(),
                    _ => "any".to_string(),
                };

                let (params_formatted_str, class_param, is_dsc_gm) =
                    self.get_method_parameters(gc, sd)?;

                let condition = self.format_condition(condition_content)?;

                let na_call = format!(
                    r#"  _rudder_common_report_na -ComponentName "{}" -ComponentKey "{}" -Message "Not applicable" -ReportId $ReportId -TechniqueName $TechniqueName -AuditOnly:$AuditOnly"#,
                    component, class_param
                );

                let call = if is_dsc_gm {
                    format!(
                        r#"  $LocalClasses = Merge-ClassContext $LocalClasses $({} {} -ComponentName "{}" -ReportId $ReportId -TechniqueName $TechniqueName -AuditOnly:$AuditOnly"#,
                        pascebab_case(&component),
                        params_formatted_str,
                        component,
                    )
                } else {
                    na_call.clone()
                };

                if condition == "any" {
                    Ok(call)
                } else {
                    let formatted_condition = &format!("\n  $Condition = \"any.({})\"\n  if (Evaluate-Class $Condition $LocalClasses $SystemClasses) {{", condition);
                    Ok(if is_dsc_gm {
                        format!(
                            "{}\n  {}\n  }} else {{\n  {}\n  }}",
                            formatted_condition, call, na_call
                        )
                    } else {
                        format!("{}\n  {}\n  }}\n", formatted_condition, call)
                    })
                }
            }
            Statement::Case(_case, vec) => {
                self.reset_cases();
                map_strings_results(
                    vec.iter(),
                    |(case, vst)| {
                        let condition_content = self.format_case_expr(gc, &case.expression)?;
                        map_strings_results(
                            vst.iter(),
                            |st| self.format_statement(gc, st, condition_content.clone()),
                            "",
                        )
                    },
                    "",
                )
            }
            Statement::Fail(msg) => Ok(format!(
                "      \"method_call\" usebundle => ncf_fail({});\n",
                self.parameter_to_dsc(msg, "Fail")?
            )),
            Statement::LogDebug(msg) => Ok(format!(
                "      \"method_call\" usebundle => ncf_log({});\n",
                self.parameter_to_dsc(msg, "Log")?
            )),
            Statement::LogInfo(msg) => Ok(format!(
                "      \"method_call\" usebundle => ncf_log({});\n",
                self.parameter_to_dsc(msg, "Log")?
            )),
            Statement::LogWarn(msg) => Ok(format!(
                "      \"method_call\" usebundle => ncf_log({});\n",
                self.parameter_to_dsc(msg, "Log")?
            )),
            Statement::Return(outcome) => {
                let outcome_str = if outcome.fragment() == "kept" {
                    "success"
                } else if outcome.fragment() == "repaired" {
                    "repaired"
                } else {
                    "error"
                };
                let content = format!(
                    r#"    $State = [ComplianceStatus]::result_{}
    $Classes = _rudder_common_report -TechniqueName $TechniqueName  -Status $State -ReportId $ReportId -ComponentName "TODO" -ComponentKey "TODO" -Message "TODO" -MessageInfo "TODO" -MessageVerbose "TODO" -report:"TODO"
    @{{"status" = $State; "classes" = $Classes}}
"#,
                    outcome_str
                );
                // handle end of bundle
                self.return_condition = Some(match self.current_cases.last() {
                    None => "!any".into(),
                    Some(c) => format!("!({})", c),
                });
                Ok(content)
            }
            Statement::Noop => Ok(String::new()),
            _ => Ok(String::new()),
        }
    }

    fn format_condition(&mut self, condition: String) -> Result<String> {
        self.current_cases.push(condition.clone());
        Ok(match &self.return_condition {
            None => condition,
            Some(c) => format!("({}) -and ({})", c, condition),
        })
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

    fn generate_parameters_metadatas<'src>(&mut self, parameters: Option<TomlValue>) -> String {
        let mut params_str = String::new();

        let get_param_field = |param: &TomlValue, entry: &str| -> String {
            if let TomlValue::Table(param) = &param {
                if let Some(val) = param.get(entry) {
                    match val {
                        TomlValue::String(s) => return format!("{:?}: {:?}", entry, s),
                        _ => { }
                    }
                }
            }
            "".to_owned()
        };

        if let Some(TomlValue::Array(parameters)) = parameters {
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
        let parameters: String =
            self.generate_parameters_metadatas(meta.remove("parameters"));
        // description must be the last field
        let mut map = map_hashmap_results(meta.iter(), |(n, v)| {
            match v {
                TomlValue::String(s) => Ok((n,s)),
                _ => Err(err!(_name, "Expecting string parameters")),
            }
        })?;
        let mut metadatas = String::new();
        let mut push_metadata = |entry: &str| {
            if let Some(val) = map.remove(&entry.to_string()) {
                metadatas.push_str(&format!("# @{} {:#?}\n", entry, val));
            }
        };
        push_metadata("name");
        push_metadata("description");
        push_metadata("version");
        metadatas.push_str(&parameters);
        for (key, val) in map.iter() {
            metadatas.push_str(&format!("# @{} {}\n", key, val));
        }
        Ok(metadatas)
    }

    pub fn format_param_type(&self, value: &Value) -> String {
        String::from(match value {
            Value::String(_) => "string",
            Value::Number(_, _) => "long",
            Value::Boolean(_, _) => "bool",
            Value::EnumExpression(_) => "enum_expression",
            Value::List(_) => "list",
            Value::Struct(_) => "struct",
        })
    }
}

impl Generator for DSC {
    // TODO methods differ if this is a technique generation or not
    fn generate(
        &mut self,
        gc: &AST,
        source_file: Option<&Path>,
        dest_file: Option<&Path>,
        _generic_methods: &Path,
        technique_metadata: bool,
    ) -> Result<()> {
        let mut files: HashMap<String, String> = HashMap::new();
        // TODO add global variable definitions
        for (rn, res) in gc.resources.iter() {
            for (sn, state) in res.states.iter() {
                // This condition actually rejects every file that is not the input filename
                // therefore preventing from having an output in another directory
                // Solutions: check filename rather than path, or accept everything that is not from crate root lib
                let file_to_create = match get_dest_file(source_file, sn.file(), dest_file) {
                    Some(file) => file,
                    None => continue,
                };
                self.reset_context();

                // get header
                let header = match files.get(&file_to_create) {
                    Some(s) => s.to_string(),
                    None => {
                        if technique_metadata {
                            self.generate_ncf_metadata(rn, res)? // TODO dsc
                        } else {
                            String::new()
                        }
                    }
                };

                // get parameters
                let method_parameters: String = res
                    .parameters
                    .iter()
                    .chain(state.parameters.iter())
                    .map(|p| {
                        format!(
                            "    [Parameter(Mandatory=$True)]  [{}] ${}",
                            uppercase_first_letter(&self.format_param_type(&p.value)),
                            pascebab_case(p.name.fragment())
                        )
                    })
                    .collect::<Vec<String>>()
                    .join(",\n");

                // add default dsc parameters
                let parameters: String = format!(
                    r#"{},
    [Parameter(Mandatory=$False)] [Switch] $AuditOnly,
    [Parameter(Mandatory=$True)]  [String] $ReportId,
    [Parameter(Mandatory=$True)]  [String] $TechniqueName"#,
                    method_parameters,
                );

                // get methods
                let methods = &state
                    .statements
                    .iter()
                    .map(|st| self.format_statement(gc, st, "any".to_string()))
                    .collect::<Result<Vec<String>>>()?
                    .join("\n");
                // merge header + parameters + methods with technique file body
                let content = format!(
                    r#"# generated by rudder-lang
{header}
function {resource_name}-{state_name} {{
  [CmdletBinding()]
  param (
{parameters}
  )
  
  $LocalClasses = New-ClassContext
  $ResourcesDir = $PSScriptRoot + "\resources"

{methods}
}}"#,
                    header = header,
                    resource_name = pascebab_case(rn.fragment()),
                    state_name = pascebab_case(sn.fragment()),
                    parameters = parameters,
                    methods = methods
                );
                files.insert(file_to_create, content);
            }
        }

        // create file if needed
        if files.is_empty() {
            match dest_file {
                Some(filename) => File::create(filename).expect("Could not create output file"),
                None => return Err(Error::new("No file to create".to_owned())),
            };
        }

        // write to file
        for (name, content) in files.iter() {
            let mut file = File::create(name).expect("Could not create output file");
            file.write_all(content.as_bytes())
                .expect("Could not write content into output file");
        }
        Ok(())
    }
}

fn uppercase_first_letter(s: &str) -> String {
    let mut c = s.chars();
    match c.next() {
        None => String::new(),
        Some(f) => f.to_uppercase().collect::<String>() + c.as_str(),
    }
}

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

fn get_dest_file(input: Option<&Path>, cur_file: &str, output: Option<&Path>) -> Option<String> {
    let dest_file = match input {
        Some(filepath) => {
            if filepath.file_name() != Some(&OsStr::new(cur_file)) {
                return None;
            }
            // can unwrap here since if source_file is Some, so does dest_file (see end of compile.rs)
            match output.unwrap().to_str() {
                Some(dest_filename) => dest_filename,
                None => cur_file,
            }
        }
        None => cur_file,
    };
    Some(dest_file.to_owned())
}

#[cfg(test)]
mod tests {
    use super::*;
    use pretty_assertions::assert_eq;

    #[test]
    fn dest_file() {
        assert_eq!(
            get_dest_file(
                Some(Path::new("/path/my_file.rl")),
                "my_file.rl",
                Some(Path::new(""))
            ),
            Some("".to_owned())
        );
        assert_eq!(
            get_dest_file(
                Some(Path::new("/path/my_file.rl")),
                "my_file.rl",
                Some(Path::new("/output/file.rl.dsc"))
            ),
            Some("/output/file.rl.dsc".to_owned())
        );
        assert_eq!(
            get_dest_file(
                Some(Path::new("/path/my_file.rl")),
                "wrong_file.rl",
                Some(Path::new("/output/file.rl.dsc"))
            ),
            None
        );
    }
}
