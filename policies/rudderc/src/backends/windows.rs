// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use std::path::Path;

use anyhow::{bail, Result};
use askama::Template;
use rudder_commons::Escaping;

use super::Backend;
use crate::ir::{
    condition::Condition,
    technique::{ItemKind, LeafReportingMode, Method, Parameter},
    Technique,
};

pub struct Windows;

impl Default for Windows {
    fn default() -> Self {
        Self::new()
    }
}

impl Backend for Windows {
    fn generate(
        &self,
        technique: Technique,
        resources: &Path,
        _standalone: bool,
    ) -> Result<String> {
        // Powershell requires a BOM added at the beginning of all files when using UTF8 encoding
        // See https://docs.microsoft.com/en-us/windows/desktop/intl/using-byte-order-marks
        // Bom for UTF-8 content, three bytes: EF BB BF https://en.wikipedia.org/wiki/Byte_order_mark
        const UTF8_BOM: &[u8; 3] = &[0xef, 0xbb, 0xbf];
        let mut with_bom = String::from_utf8(UTF8_BOM.to_vec()).unwrap();
        with_bom.push_str(&Self::technique(technique, resources)?);
        Ok(with_bom)
    }
}

#[derive(Template)]
#[template(path = "technique.ps1.askama", escape = "none")]
struct TechniqueTemplate<'a> {
    id: &'a str,
    has_modules: bool,
    parameters: Vec<Parameter>,
    methods: Vec<WindowsMethod>,
}

/// Filters for the technique template
pub mod filters {
    use std::fmt::Display;

    use anyhow::Error;
    use rudder_commons::{regex_comp, Escaping, Target};

    use crate::ir::value::Expression;

    fn uppercase_first_letter(s: &str) -> String {
        let mut c = s.chars();
        match c.next() {
            None => String::new(),
            Some(f) => f.to_uppercase().collect::<String>() + c.as_str(),
        }
    }

    /// Format an expression to be evaluated by the agent
    pub fn value_fmt<T: Display>(s: T) -> askama::Result<String> {
        let expr: Expression = s
            .to_string()
            .parse()
            .map_err(|e: Error| askama::Error::Custom(e.into()))?;
        Ok(expr.fmt(Target::Windows))
    }

    /// `my_method` -> `My-Method`
    pub fn dsc_case<T: Display>(s: T) -> askama::Result<String> {
        Ok(s.to_string()
            .split('_')
            .map(uppercase_first_letter)
            .collect::<Vec<String>>()
            .join("-"))
    }

    /// `my_test-method` -> `MyTestMethod`
    pub fn camel_case<T: Display>(s: T) -> askama::Result<String> {
        Ok(s.to_string()
            .split(['-', '_'])
            .map(uppercase_first_letter)
            .collect::<Vec<String>>()
            .join(""))
    }

    pub fn escape_double_quotes<T: Display>(s: T) -> askama::Result<String> {
        Ok(s.to_string().replace('\"', "`\""))
    }

    pub fn canonify_condition<T: Display>(s: T) -> askama::Result<String> {
        let s = s.to_string();
        if !s.contains("${") {
            Ok(format!("\"{s}\""))
        } else {
            // TODO: does not handle nested vars, we need a parser for this.
            let var = regex_comp!(r"(\$\{[^\}]*})");
            // Format expression for Windows too
            value_fmt(format!(
                "\"{}\"",
                var.replace_all(&s, r#"" + ([Rudder.Condition]::canonify($1)) + ""#)
            ))
        }
    }

    pub fn parameter_fmt(p: &&(String, String, Escaping)) -> askama::Result<String> {
        // Format expression for Windows
        let value = value_fmt(&p.1)?;
        // Then display depending on the type
        Ok(match p.2 {
            Escaping::String => format!("\"{}\"", escape_double_quotes(value)?),
            Escaping::HereString => format!("@'\n{value}\n'@"),
            Escaping::Raw => value,
        })
    }
}

struct WindowsMethod {
    id: String,
    class_prefix: String,
    component_name: String,
    component_key: String,
    disable_reporting: bool,
    condition: Option<String>,
    args: Vec<(String, String, Escaping)>,
    name: String,
}

fn method_call(m: Method, condition: Condition) -> Result<WindowsMethod> {
    let Some(report_parameter) = m.params.get(&m.info.unwrap().class_parameter) else {
        bail!("Missing parameter {}", m.info.unwrap().class_parameter)
    };
    let condition = condition.and(&m.condition);

    // Let's build a (Name, Value, Type) tuple required for proper rendering.
    let mut args: Vec<(String, String, Escaping)> = m
        .params
        .clone()
        .into_iter()
        .map(|(n, v)| {
            // Extract parameter type
            // The technique has been linted, the parameter name is correct.
            let p_type = m
                .info
                .unwrap()
                .parameter
                .iter()
                .find(|p| p.name == n)
                .unwrap()
                .escaping;
            let n_formatted = filters::camel_case(n).unwrap();
            (n_formatted, v, p_type)
        })
        .collect();

    // We want a stable output
    args.sort();

    Ok(WindowsMethod {
        id: m.id.to_string(),
        class_prefix: m.info.as_ref().unwrap().class_prefix.clone(),
        component_name: m.name,
        component_key: report_parameter.to_string(),
        disable_reporting: m.reporting.mode == LeafReportingMode::Disabled,
        condition: if condition.is_defined() {
            // If true, no need to add conditional expression
            None
        } else {
            Some(condition.to_string())
        },
        args,
        name: filters::dsc_case(&m.info.as_ref().unwrap().bundle_name).unwrap(),
    })
}

impl Windows {
    pub fn new() -> Self {
        Self
    }

    pub fn technique_name(s: &str) -> String {
        filters::dsc_case(s).unwrap()
    }

    fn technique(src: Technique, resources: &Path) -> Result<String> {
        // Extract methods
        fn resolve_module(r: ItemKind, context: Condition) -> Result<Vec<WindowsMethod>> {
            match r {
                ItemKind::Block(r) => {
                    let mut calls: Vec<WindowsMethod> = vec![];
                    for inner in r.items {
                        calls.extend(resolve_module(inner, context.and(&r.condition))?);
                    }
                    Ok(calls)
                }
                ItemKind::Method(r) => {
                    let method: Vec<WindowsMethod> = vec![method_call(r, context)?];
                    Ok(method)
                }
                _ => todo!(),
            }
        }

        let mut methods = vec![];
        for item in src.items {
            for call in resolve_module(item, Condition::Defined)? {
                methods.push(call);
            }
        }

        let technique = TechniqueTemplate {
            id: &src.id.to_string(),
            has_modules: !Windows::list_resources(resources)?.is_empty(),
            parameters: src.params,
            methods,
        };
        technique.render().map_err(|e| e.into())
    }
}

#[cfg(test)]
mod tests {
    use pretty_assertions::assert_eq;

    use crate::backends::windows::filters::canonify_condition;

    #[test]
    fn it_canonifies_conditions() {
        let c = "debian";
        let r = "\"debian\"";
        let res = canonify_condition(c).unwrap();
        assert_eq!(res, r);

        let c = "debian|ubuntu";
        let r = "\"debian|ubuntu\"";
        let res = canonify_condition(c).unwrap();
        assert_eq!(res, r);

        let c = "${var}";
        let r = "\"\" + ([Rudder.Condition]::canonify(${var})) + \"\"";
        let res = canonify_condition(c).unwrap();
        assert_eq!(res, r);

        let c = "${my_cond}.debian|${sys.${plouf}}";
        let r = r#""" + ([Rudder.Condition]::canonify(${my_cond})) + ".debian|" + ([Rudder.Condition]::canonify(${sys.${plouf})) + "}""#;
        let res = canonify_condition(c).unwrap();
        assert_eq!(res, r);
    }

    use crate::backends::windows::filters::camel_case;

    #[test]
    fn it_camelcase_method_params() {
        let p = "packageName";
        let r = "PackageName";
        let res = camel_case(p).unwrap();
        assert_eq!(res, r);

        let p = "package-name";
        let r = "PackageName";
        let res = camel_case(p).unwrap();
        assert_eq!(res, r);

        let p = "package_name";
        let r = "PackageName";
        let res = camel_case(p).unwrap();
        assert_eq!(res, r);

        let p = "Report-Message";
        let r = "ReportMessage";
        let res = camel_case(p).unwrap();
        assert_eq!(res, r);
    }
}
