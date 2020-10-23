use super::*;
use crate::ir::{
    enums::EnumExpressionPart,
    ir2::IR2,
    resource::{ResourceDef, Statement},
};
use std::convert::From;
use std::str;
use toml::{map::Map, value::Value as TOMLValue};

impl<'src> From<&IR2<'src>> for Technique {
    fn from(ir: &IR2<'src>) -> Self {
        let resources = ir
            .resources
            .iter()
            .filter_map(|(tk, res)| {
                if tk.from_lib() {
                    return None;
                } else {
                    return Some(res);
                }
            })
            .collect::<Vec<&ResourceDef>>();
        let resource = resources
            .get(0)
            .expect("There should only be one resource definition matching the technique");

        let meta = &resource.metadata;

        let interpolated_parameters: Vec<InterpolatedParameter> = meta
            .get("parameters")
            .expect(&format!(
                "Expected a 'parameter' field in '{}' metadatas",
                resource.name
            ))
            .as_array()
            .expect(&format!(
                "Metadata field 'parameters' of '{}' should be an array",
                resource.name
            ))
            .iter()
            .map(|p| {
                let param = p.as_table().expect(&format!(
                    "Metadata field 'parameter' of '{}' parameters metadata should be a table",
                    resource.name
                ));
                InterpolatedParameter {
                    id: extract_meta_string(param, "id"),
                    name: extract_meta_string(param, "name"),
                    description: extract_meta_string(param, "description"),
                }
            })
            .collect::<Vec<InterpolatedParameter>>();

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
        Technique {
            // can't determine a particular technique type yet
            r#type: "ncf_technique".to_owned(),
            version: 2,
            data: TechniqueData {
                bundle_name: resource.name.to_owned(),
                description: extract_meta_string(meta, "description"),
                name: extract_meta_string(meta, "name"),
                version: Version::from_str(&extract_meta_string(meta, "version")).unwrap(),
                category: extract_meta_string(meta, "category"),
                interpolated_parameters,
                method_calls,
                resources: Vec::new(),
            },
        }
    }
}

fn extract_meta_string(map: &Map<String, TOMLValue>, field: &str) -> String {
    map.get(field)
        .expect(&format!("Missing '{}' metadata", field))
        .as_str()
        .expect(&format!("Expected type string for '{}' metadata", field))
        .to_owned()
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
                ir.enum_list.get_item_cfengine_name(*var, *item)
            } else {
                // TODO ADD PREFIX ?
                // concat var name + item
                // TODO there may still be some conflicts with var or enum containing '_'
                format!("{}_{}", var.fragment(), item.fragment())
            }
        }
        EnumExpressionPart::RangeCompare(var, tree, left, right) => unimplemented!(), // TODO
        EnumExpressionPart::Default(_) => "any".to_owned(),
        EnumExpressionPart::NoDefault(_) => "".to_owned(),
    }
}

fn statement_to_method_call(ir: &IR2, stmt: &Statement, condition: String) -> Vec<MethodCall> {
    match stmt {
        Statement::StateDeclaration(s) => {
            let method_name = format!("{}_{}", *s.resource, *s.state);
            let parameters =
                fetch_method_parameters(ir, s, |name, value| Parameter::new(name, value));
            vec![MethodCall {
                parameters,
                condition,
                method_name,
                component: extract_meta_string(&s.metadata, "component"),
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
