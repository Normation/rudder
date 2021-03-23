use super::*;
use crate::ir::{
    enums::EnumExpressionPart,
    ir2::IR2,
    resource::{ResourceDef, StateDef, Statement},
};
use crate::parser::Token;
use std::str;
use std::{collections::HashMap, convert::From};
use toml::map::Map as TomlMap;
use toml::Value as TomlValue;

impl<'src> Technique {
    pub fn from_ir(ir: &IR2<'src>) -> Result<Self> {
        let resources = ir
            .resources
            .iter()
            .filter_map(|(tk, res)| {
                if res.metadata.get("library") == Some(&TomlValue::String("std".to_owned())) {
                    return None;
                } else {
                    return Some(res);
                }
            })
            .collect::<Vec<&ResourceDef>>();

        // TODO handle the case where there are several resources, ie wrap in loop and generate one or several Techniques
        let resource = match resources.get(0) {
            Some(r) => r,
            None => {
                return fail!(
                    Token::new("Technique", ""),
                    "There was no resource to create a Technique from"
                )
            }
        };

        let meta = &resource.metadata;

        let (name, description, version, category, interpolated_parameters) =
            get_metadatas(meta, resource.name)?;

        let method_calls: Vec<MethodCall> = resource
            .states
            .iter()
            .flat_map(|(_, state)| {
                state
                    .statements
                    .iter()
                    .flat_map(|stmt| statement_to_method_call(ir, stmt, "any".to_owned()))
                    .collect::<Vec<MethodCall>>()
            })
            .collect::<Vec<MethodCall>>();

        // TODO unit tests
        Ok(Technique {
            // can't determine a particular technique type yet
            r#type: "ncf_technique".to_owned(),
            version: 2,
            data: TechniqueData {
                bundle_name: resource.name.to_owned(),
                description,
                name,
                version: Version::from_str(&version).unwrap(),
                category,
                interpolated_parameters,
                method_calls,
                resources: Vec::new(),
            },
        })
    }
}

fn get_metadatas(
    metadata: &TomlMap<String, TomlValue>,
    name: &str,
) -> Result<(String, String, String, String, Vec<InterpolatedParameter>)> {
    let mut errors: Vec<Error> = Vec::new();
    let mut push_err = |err: &str| errors.push(Error::new(format!("'{}': {}", name, err)));

    let mut name = String::new();
    let mut description = String::new();
    let mut version = String::new();
    let mut category = String::new();
    let mut parameters = Vec::new();

    match metadata.get("name") {
        Some(TomlValue::String(s)) => name = s.to_owned(),
        Some(_) => push_err("'name' metadata must be a string"),
        None => push_err("'name' metadata is mandatory"),
    };
    match metadata.get("description") {
        Some(TomlValue::String(s)) => description = s.to_owned(),
        Some(_) => push_err("'description' metadata must be a string"),
        None => push_err("'description' metadata is mandatory"),
    };
    match metadata.get("version") {
        Some(TomlValue::String(s)) => version = s.to_owned(),
        Some(_) => push_err("'version' metadata must be a string"),
        None => push_err("'version' metadata is mandatory"),
    };
    match metadata.get("category") {
        Some(TomlValue::String(s)) => category = s.to_owned(),
        Some(_) => push_err("'category' metadata must be a string"),
        None => push_err("'category' metadata is mandatory"),
    };

    let mut get_value_from_key = |map: &TomlMap<String, TomlValue>, key: &str| -> Result<String> {
        match map.get(key) {
            Some(v) => match v {
                TomlValue::String(s) => Ok(s.to_owned()),
                _ => Err(Error::new(format!("'{}': {}", name, "'parameters' metadata must be an array of tables of (String, String) pairs")))
            },
            None => Err(Error::new(format!("'{}': {}", name, "'parameters' metadata content must include the following informations: id, name, description")))
        }
    };

    match metadata.get("parameters") {
        Some(TomlValue::Array(values)) => {
            for v in values {
                if let TomlValue::Table(map) = v {
                    let mut id = String::new();
                    let mut name = String::new();
                    let mut description = String::new();
                    match map.get("id") {
                        Some(v) => match v {
                            TomlValue::String(s) => id = s.to_owned(),
                            _ => push_err("'parameters.id' metadata must be a String"),
                        },
                        None => push_err(
                            "'parameters' metadata must include the following information: id",
                        ),
                    };
                    match map.get("name") {
                        Some(v) => match v {
                            TomlValue::String(s) => name = s.to_owned(),
                            _ => push_err("'parameters.name' metadata must be a String"),
                        },
                        None => push_err(
                            "'parameters' metadata must include the following information: name",
                        ),
                    };
                    match map.get("description") {
                        Some(v) => match v {
                            TomlValue::String(s) => description = s.to_owned(),
                            _ => push_err("'parameters.description' metadata must be a String"),
                        },
                        None => push_err(
                            "'parameters' metadata must include the following information: description",
                        ),
                    };
                    parameters.push(InterpolatedParameter {
                        id,
                        name,
                        description,
                    });
                } else {
                    push_err("'parameters' metadata must be an array of strings")
                }
            }
        }
        Some(_) => push_err("'parameters' metadata must be an array of strings"),
        None => push_err("'parameters' metadata is mandatory"),
    };

    if !errors.is_empty() {
        return Err(Error::from_vec(errors));
    }

    Ok((name, description, version, category, parameters))
}

fn format_expr(ir: &IR2, expr: &EnumExpressionPart) -> String {
    match expr {
        EnumExpressionPart::And(e1, e2) => {
            let mut lexpr = format_expr(ir, &*e1);
            let mut rexpr = format_expr(ir, &*e2);
            if lexpr.contains('|') {
                lexpr = format!("({})", lexpr);
            }
            if rexpr.contains('|') {
                rexpr = format!("({})", rexpr);
            }
            format!("{}.{}", lexpr, rexpr)
        }
        EnumExpressionPart::Or(e1, e2) => {
            format!("{}|{}", format_expr(ir, &*e1), format_expr(ir, &*e2))
        }
        EnumExpressionPart::Not(e) => {
            let mut expr = format_expr(ir, &*e);
            if expr.contains('|') || expr.contains('.') {
                expr = format!("({})", expr);
            }
            format!("!{}", expr)
        }
        EnumExpressionPart::Compare(var, tree, item) => {
            if let Some(true) = ir.enum_list.enum_is_global(*var) {
                ir.enum_list.get_cfengine_item_name(*var, *item)
            } else {
                // if var is a foreign variable, output it as it is
                if tree.fragment() == "boolean" && item.fragment() == "true" {
                    var.fragment().to_owned()
                } else {
                    // concat var name + item
                    // TODO there may still be some conflicts with var or enum containing '_'
                    // format!("{}_{}", var.fragment(), item.fragment())
                    format!(
                        "{}_${{report_data.canonified_directive_id}}_{}",
                        var.fragment(),
                        item.fragment()
                    )
                }
            }
        }
        EnumExpressionPart::RangeCompare(var, tree, left, right) => unimplemented!(), // TODO
        EnumExpressionPart::Default(_) => "any".to_owned(),
        EnumExpressionPart::NoDefault(_) => "".to_owned(),
    }
}

fn value_to_string(value: &Value, method_name: &str, string_delim: bool) -> Result<String> {
    let delim = if string_delim { "\"" } else { "" };
    Ok(match value {
        Value::String(s) => format!("{}{}{}", delim, String::try_from(s)?, delim),
        Value::Float(_, n) => format!("{}", n),
        Value::Integer(_, n) => format!("{}", n),
        Value::Boolean(_, b) => format!("{}", b),
        Value::List(l) => format!(
            "[ {} ]",
            map_strings_results(l.iter(), |x| value_to_string(value, method_name, true), ",")?
        ),
        Value::Struct(s) => unimplemented!(),
        Value::EnumExpression(_e) => unimplemented!(),
    })
}

fn statement_to_method_call(ir: &IR2, stmt: &Statement, condition: String) -> Vec<MethodCall> {
    match stmt {
        Statement::ConditionVariableDefinition(s) => {
            let method_name = format!("{}_{}", *s.resource, *s.state);
            let parameters =
                fetch_method_parameters(ir, &s.to_method(), |name, value, _metadatas| {
                    Parameter::new(
                        name,
                        &value_to_string(value, &method_name, false)
                            .expect("Value is not formatted correctly"),
                    )
                });
            vec![MethodCall {
                parameters,
                condition,
                method_name,
                component: s.metadata.get("component").and_then(|c| {
                    Some(
                        c.as_str()
                            .expect("Expected type string for 'component' metadata")
                            .to_owned(),
                    )
                }),
            }]
        }
        Statement::StateDeclaration(s) => {
            let method_alias = s
                .metadata
                .get("method_alias")
                .and_then(|v| v.as_str())
                .map(String::from);
            let method_name = if let Some(method_alias_content) = method_alias {
                method_alias_content
            } else {
                format!("{}_{}", *s.resource, *s.state)
            };
            let mut parameters = fetch_method_parameters(ir, s, |name, value, _| {
                Parameter::new(
                    name,
                    &value_to_string(value, &method_name, false)
                        .expect("Value is not formatted correctly"),
                )
            });

            // EXCEPTION: reunite variable_string_escaped resource parameters that appear to be joined from cfengine side
            if method_name == "variable_string_escaped" {
                let merged_values = parameters
                    .iter()
                    .map(|p| p.value.clone())
                    .collect::<Vec<String>>()
                    .join(".");
                parameters = vec![Parameter::new("variable_name", &merged_values)];
            };

            vec![MethodCall {
                parameters,
                condition,
                method_name,
                component: s.metadata.get("component").and_then(|c| {
                    Some(
                        c.as_str()
                            .expect("Expected type string for 'component' metadata")
                            .to_owned(),
                    )
                }),
            }]
        }
        Statement::Case(_, enum_expressions) => enum_expressions
            .iter()
            .flat_map(|(enum_expr, stmts)| {
                stmts
                    .iter()
                    .flat_map(|stmt| {
                        statement_to_method_call(ir, stmt, format_expr(ir, &enum_expr.expression))
                    })
                    .collect::<Vec<MethodCall>>()
            })
            .collect::<Vec<MethodCall>>(),
        _ => Vec::new(),
    }
}
