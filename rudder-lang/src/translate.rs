// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use crate::error::*;
use crate::parser::Token;
use crate::compile::parse_stdlib;
use crate::parser::PAST;
use crate::ast::AST;
use crate::ast::value;
use crate::io::IOContext;
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

pub fn translate_file(context: &IOContext) -> Result<()> {
    let input_path = context.source.to_string_lossy();
    let output_path = context.dest.to_string_lossy();
    let generic_methods_path = context.generic_methods.to_str().unwrap();
    let file_error = |filename: &str, err| err!(Token::new(&filename.to_owned(), ""), "{}", err);

    info!(
        "{} of {} into {}",
        "Processing translation".bright_green(),
        input_path.bright_yellow(),
        output_path.bright_yellow()
    );

    info!(
        "|- {} {}",
        "Reading".bright_green(),
        generic_methods_path.bright_yellow()
    );
    let config_data =
        fs::read_to_string(generic_methods_path).map_err(|e| file_error(generic_methods_path, e))?;
    let configuration: toml::Value =
        toml::from_str(&config_data).map_err(|e| err!(Token::new(generic_methods_path, ""), "{}", e))?;

    info!(
        "|- {}",
        "Reading stdlib".bright_green()
    );
    let sources = Arena::new();
    let mut past = PAST::new();
    parse_stdlib(&mut past, &sources, &context.stdlib)?;
    let stdlib = AST::from_past(past)?;

    info!(
        "|- {} {}",
        "Reading".bright_green(),
        input_path.bright_yellow()
    );
    let json_data = fs::read_to_string(&context.source).map_err(|e| file_error(&input_path, e))?;
    let technique = serde_json::from_str::<Technique>(&json_data)
        .map_err(|e| err!(Token::new(&input_path, ""), "{}", e))?;

    info!(
        "|- {} (translation phase)",
        "Generating output code".bright_green()
    );
    let translator = Translator { stdlib, technique, configuration };
    let rl_technique = translator.translate()?;
    fs::write(&context.dest, rl_technique).map_err(|e| file_error(&output_path, e))?;
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

    fn get_method_from_stdlib(&self, method_name: &str) -> Option<(String, String)> {
        let matched_pairs: Vec<(&str, &str)> = self.stdlib
            .resources
            .iter()
            .filter_map(|(res_as_tk, resdef)| {
                if method_name.starts_with(**res_as_tk) {
                    let matched_pairs = resdef.states.iter().filter_map(|(state_as_tk, _)| {
                        if method_name == &format!("{}_{}", **res_as_tk, **state_as_tk) {
                            return Some((**res_as_tk, **state_as_tk))
                        }
                        None
                    }).collect::<Vec<(&str, &str)>>();
                    return Some(matched_pairs)
                }
                None
            })
            .flatten()
            .collect();
        match matched_pairs.as_slice() {
            [] => None,
            [(resource, state)] => Some(((*resource).to_owned(), (*state).to_owned())),
            _ => panic!(format!("The standard library contains several matches for the following method: {}", method_name))
        }
    }
    
    fn translate_call(&self, call: &MethodCall) -> Result<String> {
        let (resource, state) = match self.get_method_from_stdlib(&call.method_name) {
            Some(res) => res,
            None => return Err(Error::User(format!("Invalid method name '{}'", call.method_name)))
        };

        // split argument list
        let rconf = match self.configuration.get("resources") {
            None => return Err(Error::User("No resources section in config.toml".into())),
            Some(m) => m,
        };
        let res_arg_v = match rconf.get(&resource) {
            None => toml::value::Value::Integer(1),
            Some(r) => r.clone(),
        };

        let res_arg_count: usize = match res_arg_v.as_integer() {
            None => {
                return Err(Error::User(format!(
                    "Resource prefix '{}' must have a number as its parameter count",
                    &resource
                )))
            }
            Some(v) => v as usize,
        };
        let it = &mut call.args.iter();
        let res_args = map_strings_results(it.take(res_arg_count), |x| self.translate_arg(x), ",")?;
        let st_args = map_strings_results(it, |x| self.translate_arg(x), ",")?;

        // call formating
        let call_str = format!("{}({}).{}({})", &resource, res_args, &state, st_args);
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
