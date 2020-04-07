// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use crate::error::*;
use crate::parser::Token;
use crate::compile::parse_stdlib;
use crate::parser::PAST;
use crate::ast::AST;
use crate::ast::value;
use colored::Colorize;
use lazy_static::lazy_static;
use nom::branch::alt;
use nom::bytes::complete::*;
use nom::character::complete::*;
use nom::combinator::*;
use nom::multi::many1;
use nom::sequence::*;
use nom::IResult;
use regex::{Regex, Captures};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::convert::TryFrom;
use std::fs;
use std::path::{Path, PathBuf};
use std::str;
use toml;
use typed_arena::Arena;

#[derive(Serialize, Deserialize)]
struct Technique {
    name: String,
    description: String,
    version: String,
    bundle_name: String,
    parameter: Vec<Value>,
    bundle_args: Vec<String>,
    method_calls: Vec<MethodCall>,
}

#[derive(Serialize, Deserialize)]
struct MethodCall {
    method_name: String,
    class_context: String,
    args: Vec<String>,
    component: String,
}

pub fn translate_file(json_file: &Path, rl_file: &Path, config_filename: &Path) -> Result<()> {
    let input_filename = &json_file.to_string_lossy();
    let output_filename = &rl_file.to_string_lossy();
    let config_filename = config_filename.to_str().unwrap();
    let file_error = |filename: &str, err| err!(Token::new(&filename.to_owned(), ""), "{}", err);

    info!(
        "{} of {} into {}",
        "Processing translation".bright_green(),
        input_filename.bright_yellow(),
        output_filename.bright_yellow()
    );

    info!(
        "|- {} {}",
        "Reading".bright_green(),
        config_filename.bright_yellow()
    );
    let config_data =
        fs::read_to_string(config_filename).map_err(|e| file_error(config_filename, e))?;
    let configuration: toml::Value =
        toml::from_str(&config_data).map_err(|e| err!(Token::new(config_filename, ""), "{}", e))?;

    info!(
        "|- {}",
        "Reading stdlib".bright_green()
    );
    let sources = Arena::new();
    let mut past = PAST::new();
    let libs_dir = &PathBuf::from("./libs/"); // TODO
    parse_stdlib(&mut past, &sources, libs_dir)?;
    let stdlib = AST::from_past(past)?;

    info!(
        "|- {} {}",
        "Reading".bright_green(),
        input_filename.bright_yellow()
    );
    let json_data = fs::read_to_string(&json_file).map_err(|e| file_error(input_filename, e))?;
    let technique = serde_json::from_str::<Technique>(&json_data)
        .map_err(|e| err!(Token::new(input_filename, ""), "{}", e))?;

    info!(
        "|- {} (translation phase)",
        "Generating output code".bright_green()
    );
    let mut translator = Translator { stdlib, technique, configuration };
    let rl_technique = translator.translate()?;
    fs::write(&rl_file, rl_technique).map_err(|e| file_error(output_filename, e))?;
    Ok(())
}

struct Translator<'src> {
    stdlib: AST<'src>,
    technique: Technique,
    configuration: toml::Value,
}

impl<'src> Translator<'src> {
    fn translate(&self) -> Result<String> {
        let parameters_meta = self.translate_meta_parameters(&self.technique.parameter).unwrap();
        let parameters = self.technique.bundle_args.join(",");
        let calls = map_strings_results(
            self.technique.method_calls.iter(),
            |c| self.translate_call(c),
            "\n",
        )?;
        let out = format!(
            r#"# This file has been generated with rltranslate
@format=0
@name="{name}"
@description="{description}"
@version="{version}"
@parameters= [{newline}{parameters_meta}]
resource {bundle_name}({parameters})
{bundle_name} state technique() {{
{calls}
}}
"#,
            description = self.technique.description,
            version = self.technique.version,
            name = self.technique.name,
            bundle_name = self.technique.bundle_name,
            newline = "\n",
            parameters_meta = parameters_meta,
            parameters = parameters,
            calls = calls
        );
        Ok(out)
    }

    fn translate_call(&self, call: &MethodCall) -> Result<String> {
        lazy_static! {
            static ref RE: Regex = Regex::new(r"^([a-z]+)_(\w+)$").unwrap();
            static ref RE_KERNEL_MODULE: Regex = Regex::new(r"^(kernel_module)_(\w+)$").unwrap();
        }

        // `kernel_module` uses the main `_` resource/state separator
        // so an exception is required to parse it properly
        // dealt with the exception first, then with the common case
        let (resource, state) = match RE_KERNEL_MODULE.captures(&call.method_name) {
            Some(caps) => (caps.get(1).unwrap().as_str(), caps.get(2).unwrap().as_str()),
            // here is the common case
            None => match RE.captures(&call.method_name) {
                Some(caps) => (caps.get(1).unwrap().as_str(), caps.get(2).unwrap().as_str()),
                None => {
                    return Err(Error::User(format!(
                        "Invalid method name '{}'",
                        call.method_name
                    )))
                }
            },
        };

        // split argument list
        let rconf = match self.configuration.get("resources") {
            None => return Err(Error::User("No resources section in config.toml".into())),
            Some(m) => m,
        };
        let res_arg_v = match rconf.get(resource) {
            None => toml::value::Value::Integer(1),
            Some(r) => r.clone(),
        };

        let res_arg_count: usize = match res_arg_v.as_integer() {
            None => {
                return Err(Error::User(format!(
                    "Resource prefix '{}' must have a number as its parameter count",
                    resource
                )))
            }
            Some(v) => v as usize,
        };
        let it = &mut call.args.iter();
        let res_args = map_strings_results(it.take(res_arg_count), |x| self.translate_arg(x), ",")?;
        let st_args = map_strings_results(it, |x| self.translate_arg(x), ",")?;

        // call formating
        let call_str = format!("{}({}).{}({})", resource, res_args, state, st_args);
        let out_state = if call.class_context == "any" {
            format!("  {}", call_str)
        } else {
            let condition = self.translate_condition(&call.class_context)?;
            format!("  if {} => {}", condition, call_str)
        };

        // outcome detection and formating
        let mconf = match self.configuration.get("methods") {
            None => return Err(Error::User("No methods section in config.toml".into())),
            Some(m) => m,
        };
        let method = match mconf.get(&call.method_name) {
            None => {
                return Err(Error::User(format!(
                    "Unknown generic method call: {}",
                    &call.method_name
                )))
            }
            Some(m) => m,
        };
        let class_prefix = match method.get("class_prefix") {
            None => {
                return Err(Error::User(format!(
                    "Undefined class_prefix for {}",
                    &call.method_name
                )))
            }
            Some(m) => m.as_str().unwrap(),
        };
        let class_parameter_id = match method.get("class_parameter_id") {
            None => {
                return Err(Error::User(format!(
                    "Undefined class_parameter_id for {}",
                    &call.method_name
                )))
            }
            Some(m) => m.as_integer().unwrap(),
        };
        let class_parameter_value = &call.args[class_parameter_id as usize];
        let canonic_parameter = canonify(class_parameter_value);
        let outcome = format!(" as {}_{}", class_prefix, canonic_parameter);
        // TODO remove outcome if there is no usage
        Ok(format!(
            "  @component = \"{}\"\n{}{}",
            &call.component, out_state, outcome
        ))
    }

    fn translate_meta_parameters(&self, parameters: &[Value]) -> Result<String> {
        let mut parameters_meta = String::new();
        for param in parameters {
            match param.as_object() {
                Some(map) => {
                    let name = &map
                        .get("name")
                        .expect("Unable to parse name parameter")
                        .to_string();
                    let id = &map
                        .get("id")
                        .expect("Unable to parse id parameter")
                        .to_string();
                    let constraints = &map
                        .get("constraints")
                        .expect("Unable to parse constraints parameter")
                        .to_string();
                    parameters_meta.push_str(&format!(
                        r#"  {{ "name": {}, "id": {}, "constraints": {} }}{}"#,
                        name, id, constraints, ",\n"
                    ));
                }
                None => return Err(Error::User(String::from("Unable to parse meta parameters"))),
            }
        }
        // let parameters_meta = serde_json::to_string(&technique.parameter);
        // if parameters_meta.is_err() {
        // return Err(Error::User("Unable to parse technique file".to_string()));
        // }
        Ok(parameters_meta)
    }

    fn translate_arg(&self, arg: &str) -> Result<String> {
        let var = match parse_cfstring(arg) {
            Err(_) => return Err(Error::User(format!("Invalid variable syntax in '{}'", arg))),
            Ok((_, o)) => o,
        };

        map_strings_results(var.iter(), |x| Ok(format!("\"{}\"", x.to_string()?)), ",")
    }

    fn translate_condition(&self, expr: &str) -> Result<String> {
        lazy_static! {
            static ref CLASS_RE: Regex = Regex::new(r"([\w${}.]+)").unwrap();
            static ref ANY_RE: Regex = Regex::new(r"(any\.)").unwrap();
        }
        let no_any = ANY_RE.replace_all(expr, "");
        let mut errs = Vec::new();
        // replace all matching words as classes
        let result = CLASS_RE.replace_all(&no_any, |caps: &Captures| {
            match self.translate_class(&caps[1]) {
                Ok(s) => s,
                Err(e) => {errs.push(e); "".into()}
            }
        });
        if errs.is_empty() {
            Ok(result.into())
        } else {
            Err(Error::from_vec(errs))
        }
    }

    fn translate_class(&self, cond: &str) -> Result<String> {
        lazy_static! {
        static ref METHOD_RE: Regex = Regex::new(r"^(\w+)_(\w+)$").unwrap();
    }

        // detect known system class
        for i in self.stdlib.enum_list.enum_item_iter("system".into()) {
            match self.stdlib.enum_list.enum_item_metadata("system".into(),*i).expect("Enum item exists").get(&"cfengine_name".into()) {
                None => if **i == cond { return Ok(cond.into()); },  // no @cfengine_name -> enum item = cfengine class
                Some(value::Value::String(name)) => if String::try_from(name)? == cond { return Ok((**i).into()); }, // simple cfengine name
                Some(value::Value::List(list)) => for value in list {
                    if let value::Value::String(name) = value {
                        if String::try_from(name)? == cond {
                            return Ok((**i).into());
                        }
                    }
                }, // list of cfengine names
                _ => return Err(Error::User(format!("@cfengine_name must be a string or a list '{}'", *i))),
            }
        }

        // detect group classes
        if cond.starts_with("group_") {
            // group classes are implemented with boolean variables of the sane name
            // TODO declare the variable
            return Ok(cond.into())
        }

        // detect method outcome class
        if let Some(caps) = METHOD_RE.captures(cond) {
            let (method, status) = (caps.get(1).unwrap().as_str(), caps.get(2).unwrap().as_str());
            if vec!["kept", "success"].iter().any(|x| x == &status) {
                return Ok(format!("{} =~ success", method));
            } else if vec!["error", "not_ok", "failed", "denied", "timeout"]
                .iter()
                .any(|x| x == &status)
            {
                return Ok(format!("{} =~ error", method));
            } else if vec!["repaired", "ok", "reached"]
                .iter()
                .any(|x| x == &status)
            {
                return Ok(format!("{} =~ {}", method, status));
            }
        };

        Err(Error::User(format!(
            "Don't know how to handle class '{}'",
            cond
        )))
    }
}

/// Canonify a string the same way cfengine does
fn canonify(input: &str) -> String {
    let s = input
        .as_bytes()
        .iter()
        .map(|x| {
            if x.is_ascii_alphanumeric() || *x == b'_' {
                *x
            } else {
                b'_'
            }
        })
        .collect::<Vec<u8>>();
    str::from_utf8(&s)
        .unwrap_or_else(|_| panic!("Canonify failed on {}", input))
        .to_owned()
}

#[derive(Clone)]
struct CFVariable {
    ns: Option<String>,
    name: String,
}
fn parse_cfvariable(i: &str) -> IResult<&str, CFVariable> {
    map(
        tuple((
            opt(map(
                terminated(
                    take_while1(|c: char| c.is_alphanumeric() || (c == '_')),
                    tag("."),
                ),
                |x: &str| x.into(),
            )),
            map(
                take_while1(|c: char| c.is_alphanumeric() || (c == '_')),
                |x: &str| x.into(),
            ),
        )),
        |(ns, name)| CFVariable { ns, name },
    )(i)
}
#[derive(Clone)]
enum CFStringElt {
    Static(String),       // static content
    Variable(CFVariable), // variable name
}
impl CFStringElt {
    fn to_string(&self) -> Result<String> {
        Ok(match self {
            CFStringElt::Static(s) => s.to_string(),
            CFStringElt::Variable(v) => {
                match &v.ns {
                    None => v.name.clone(), // a parameter
                    Some(ns) => match ns.as_ref() {
                        "const" => (match v.name.as_ref() {
                            "dollar" => "$",
                            "dirsep" => "/",
                            "endl" => "\\n",
                            "n" => "\\n",
                            "r" => "\\r",
                            "t" => "\\t",
                            _ => {
                                return Err(Error::User(format!(
                                    "Unknown constant '{}.{}'",
                                    ns, v.name
                                )))
                            }
                        })
                        .into(),
                        "sys" => {
                            return Err(Error::User(format!(
                                "Not implemented variable namespace sys '{}.{}'",
                                ns, v.name
                            )))
                        }
                        "this" => {
                            return Err(Error::User(format!(
                                "Unsupported variable namespace this '{}.{}'",
                                ns, v.name
                            )))
                        }
                        ns => format!("${{{}.{}}}", ns, v.name),
                    },
                }
                // TODO
                // - array -> ?
                // - list -> ?
            }
        })
    }
}
fn parse_cfstring(i: &str) -> IResult<&str, Vec<CFStringElt>> {
    // There is a rest inside so this just serve as a guard
    all_consuming(alt((
        many1(alt((
            // variable ${}
            map(
                delimited(tag("${"), parse_cfvariable, tag("}")),
                CFStringElt::Variable,
            ),
            // variable $()
            map(
                delimited(tag("$("), parse_cfvariable, tag(")")),
                CFStringElt::Variable,
            ),
            // constant
            map(take_until("$"), |s: &str| CFStringElt::Static(s.into())),
            // end of string
            map(
                preceded(
                    peek(anychar), // do no take rest if we are already at the end
                    rest,
                ),
                |s: &str| CFStringElt::Static(s.into()),
            ),
        ))),
        // empty string
        value(vec![CFStringElt::Static("".into())], not(anychar)),
    )))(i)
}
