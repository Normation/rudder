// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

pub mod test;

use std::path::Path;

use anyhow::{Result, bail};
use askama::Template;
use rudder_commons::{Escaping, PolicyMode, methods::method::Agent};

use super::Backend;
use crate::ir::{
    Technique,
    condition::Condition,
    technique::{ItemKind, LeafReportingMode, Method, Parameter},
};

pub struct Windows;

#[cfg(unix)]
pub const POWERSHELL_BIN: &str = "pwsh";
#[cfg(windows)]
pub const POWERSHELL_BIN: &str = "PowerShell.exe";

pub const POWERSHELL_OPTS: &[&str] = &["-NoProfile", "-NonInteractive"];

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
    has_resources: bool,
    parameters: Vec<Parameter>,
    methods: Vec<WindowsMethod>,
}

/// Filters for the technique template
pub mod filters {
    use std::fmt::Display;

    use anyhow::Error;
    use rudder_commons::{Escaping, PolicyMode, Target};

    use crate::ir::{technique, value::Expression};

    pub fn uppercase_first_letter(s: &str) -> String {
        let mut c = s.chars();
        match c.next() {
            None => String::new(),
            Some(f) => f.to_uppercase().collect::<String>() + c.as_str(),
        }
    }

    pub fn remove_trailing_slash<T: Display>(
        s: T,
        _: &dyn askama::Values,
    ) -> askama::Result<String> {
        let s = s.to_string();
        Ok(s.strip_suffix('/').map(|s| s.to_string()).unwrap_or(s))
    }

    /// Format an expression to be evaluated by the agent
    pub fn value_fmt<T: Display>(
        s: T,
        _: &dyn askama::Values,
        t_id: &&str,
        t_params: &Vec<technique::Parameter>,
    ) -> askama::Result<String> {
        let expr: Expression = s
            .to_string()
            .parse()
            .map_err(|e: Error| askama::Error::Custom(e.into()))?;
        let simplified_expr = expr
            .force_long_name_for_technique_params(Target::Windows, t_id, t_params.to_owned())
            .map_err(|e: Error| askama::Error::Custom(e.into()))?;
        match simplified_expr {
            Expression::Scalar(_) => {
                Ok(format!("@'\n{}\n'@", simplified_expr.fmt(Target::Windows)))
            }
            _ => Ok(simplified_expr.fmt(Target::Windows)),
        }
    }

    /// `my_method` -> `My-Method`
    pub fn dsc_case<T: Display>(s: T, _: &dyn askama::Values) -> askama::Result<String> {
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

    pub fn escape_single_quotes<T: Display>(s: T) -> askama::Result<String> {
        Ok(s.to_string().replace('\'', "`\'"))
    }

    pub fn escape_double_quotes<T: Display>(s: T) -> askama::Result<String> {
        Ok(s.to_string().replace('\"', "`\""))
    }

    pub fn technique_name<T: Display>(s: T, _: &dyn askama::Values) -> askama::Result<String> {
        Ok(super::Windows::technique_name(&s.to_string()))
    }

    pub fn canonify_condition_with_context<T: Display>(
        s: T,
        _: &dyn askama::Values,
        t_id: &&str,
        t_params: &Vec<technique::Parameter>,
    ) -> askama::Result<String> {
        let s = s.to_string();
        if !s.contains("${") {
            Ok(format!("\"{s}\""))
        } else {
            let expr: Expression = s
                .to_string()
                .parse()
                .map_err(|e: Error| askama::Error::Custom(e.into()))?;
            let simplified_expr = expr
                .force_long_name_for_technique_params(Target::Windows, t_id, t_params.to_owned())
                .map_err(|e: Error| askama::Error::Custom(e.into()))?;
            Ok(canonify_expression(simplified_expr))
        }
    }

    pub fn canonify_condition<T: Display>(s: T, _: &dyn askama::Values) -> askama::Result<String> {
        canonify_condition_stub(s)
    }

    pub fn canonify_condition_stub<T: Display>(s: T) -> askama::Result<String> {
        let s = s.to_string();
        if !s.contains("${") {
            Ok(format!("\"{s}\""))
        } else {
            let canonify_stub = "([Rudder.Condition]::Canonify(";
            let expr: Expression = s
                .to_string()
                .parse()
                .map_err(|e: Error| askama::Error::Custom(e.into()))?;
            match expr {
                Expression::Scalar(_) => Ok(format!(
                    "{}@'\n{}\n'@))",
                    canonify_stub,
                    expr.fmt(Target::Windows)
                )),
                Expression::Empty => Ok("''".to_string()),
                _ => Ok(format!("{}{}))", canonify_stub, expr.fmt(Target::Windows))),
            }
        }
    }

    pub fn canonify_expression(e: Expression) -> String {
        match e {
            Expression::Sequence(s) => {
                let sum = s
                    .into_iter()
                    .map(canonify_expression)
                    .collect::<Vec<String>>()
                    .join(" + ");
                format!("({sum})")
            }
            Expression::Scalar(_) | Expression::Empty => format!("'{}'", e.fmt(Target::Windows)),
            _ => format!("([Rudder.Condition]::Canonify({}))", e.fmt(Target::Windows)),
        }
    }

    pub fn parameter_fmt(
        p: &&(String, String, Escaping),
        _: &dyn askama::Values,
        t_id: &&str,
        t_params: &Vec<technique::Parameter>,
    ) -> askama::Result<String> {
        parameter_fmt_stub(p, t_id, t_params)
    }

    pub fn parameter_fmt_stub(
        p: &&(String, String, Escaping),
        t_id: &&str,
        t_params: &Vec<technique::Parameter>,
    ) -> askama::Result<String> {
        Ok(match p.2 {
            Escaping::String => {
                let expr: Expression =
                    p.1.to_string()
                        .parse()
                        .map_err(|e: Error| askama::Error::Custom(e.into()))?;
                let simplified_expr = expr
                    .force_long_name_for_technique_params(
                        Target::Windows,
                        t_id,
                        t_params.to_owned(),
                    )
                    .map_err(|e: Error| askama::Error::Custom(e.into()))?;
                match simplified_expr.clone() {
                    Expression::Scalar(_) => {
                        format!("@'\n{}\n'@", simplified_expr.fmt(Target::Windows))
                    }
                    Expression::Empty => "''".to_string(),
                    _ => simplified_expr.fmt(Target::Windows),
                }
            }
            // HereString are not expanded at all
            Escaping::HereString => format!("@'\n{}\n'@", p.1),
            Escaping::Raw => p.1.clone(),
        })
    }

    pub fn policy_mode_fmt(
        op: &Option<PolicyMode>,
        _: &dyn askama::Values,
    ) -> askama::Result<String> {
        match op {
            None => Ok("$policyMode".to_string()),
            Some(p) => match p {
                PolicyMode::Audit => Ok("([Rudder.PolicyMode]::Audit)".to_string()),
                PolicyMode::Enforce => Ok("([Rudder.PolicyMode]::Enforce)".to_string()),
            },
        }
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
    is_supported: bool,
    policy_mode_override: Option<PolicyMode>,
}

fn method_call(
    m: Method,
    condition: Condition,
    policy_mode_context: Option<PolicyMode>,
) -> Result<WindowsMethod> {
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

    let is_supported = m.info.unwrap().agent_support.contains(&Agent::Dsc);

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
        name: Windows::technique_name_plain(&m.info.as_ref().unwrap().bundle_name),
        is_supported,
        policy_mode_override: if let Some(x) = policy_mode_context {
            if m.policy_mode_override.is_none() {
                Some(x)
            } else {
                m.policy_mode_override
            }
        } else {
            m.policy_mode_override
        },
    })
}

impl Windows {
    pub fn new() -> Self {
        Self
    }

    pub fn technique_name(s: &str) -> String {
        format!("Technique-{}", Self::technique_name_plain(s))
    }

    pub fn technique_name_plain(s: &str) -> String {
        s.split('_')
            .map(filters::uppercase_first_letter)
            .collect::<Vec<String>>()
            .join("-")
    }

    fn technique(src: Technique, resources: &Path) -> Result<String> {
        // Extract methods
        fn resolve_module(
            r: ItemKind,
            context: Condition,
            policy_mode_context: Option<PolicyMode>,
        ) -> Result<Vec<WindowsMethod>> {
            match r {
                ItemKind::Block(r) => {
                    let mut calls: Vec<WindowsMethod> = vec![];
                    for inner in r.items {
                        calls.extend(resolve_module(
                            inner,
                            context.and(&r.condition),
                            r.policy_mode_override,
                        )?);
                    }
                    Ok(calls)
                }
                ItemKind::Method(r) => {
                    let method: Vec<WindowsMethod> =
                        vec![method_call(r, context, policy_mode_context)?];
                    Ok(method)
                }
                _ => todo!(),
            }
        }

        let mut methods = vec![];
        for item in src.items {
            for call in resolve_module(item, Condition::Defined, None)? {
                methods.push(call);
            }
        }

        let technique = TechniqueTemplate {
            id: &src.id.to_string(),
            has_resources: !Windows::list_resources(resources)?.is_empty(),
            parameters: src.params,
            methods,
        };
        technique.render().map_err(|e| e.into())
    }
}

#[cfg(test)]
mod tests {
    use std::str::FromStr;

    use pretty_assertions::assert_eq;
    use rudder_commons::Escaping;

    use crate::backends::windows::filters::{
        canonify_condition_stub, canonify_expression, parameter_fmt_stub,
    };

    #[test]
    fn it_canonifies_expressions() {
        let e = Expression::Scalar("debian".to_string());
        let r = "'debian'".to_string();
        assert_eq!(canonify_expression(e), r);

        let e = Expression::Empty;
        let r = "''".to_string();
        assert_eq!(canonify_expression(e), r);

        let e = Expression::Sys(vec![Expression::Scalar("arch".to_string())]);
        let r = r#"([Rudder.Condition]::Canonify([Rudder.Datastate]::Render('{{{' + @'
vars.sys.arch
'@ + '}}}')))"#
            .to_string();
        assert_eq!(canonify_expression(e), r);

        let e = Expression::from_str("(windows.!false).!(${sys.arch})").unwrap();
        let r = r#"('(windows.!false).!(' + ([Rudder.Condition]::Canonify([Rudder.Datastate]::Render('{{{' + @'
vars.sys.arch
'@ + '}}}'))) + ')')"#.to_string();
        assert_eq!(canonify_expression(e), r);
    }
    #[test]
    fn it_canonifies_conditions() {
        let c = "debian";
        let r = "\"debian\"";
        let res = canonify_condition_stub(c).unwrap();
        assert_eq!(res, r);

        let c = "debian|ubuntu";
        let r = "\"debian|ubuntu\"";
        let res = canonify_condition_stub(c).unwrap();
        assert_eq!(res, r);

        let c = "${var}";
        let r = "([Rudder.Condition]::Canonify([Rudder.Datastate]::Render('{{{' + @'\n\
                vars.var\n\
                '@ + '}}}')))";
        let res = canonify_condition_stub(c).unwrap();
        assert_eq!(res, r);

        let c = "${my_cond}.debian|${sys.${plouf}}";
        let r = r#"([Rudder.Condition]::Canonify(([Rudder.Datastate]::Render('{{{' + @'
vars.my_cond
'@ + '}}}')) + @'
.debian|
'@ + ([Rudder.Datastate]::Render('{{{' + @'
vars.sys.
'@ + [Rudder.Datastate]::Render('{{{' + @'
vars.plouf
'@ + '}}}') + '}}}'))))"#;
        let res = canonify_condition_stub(c).unwrap();
        assert_eq!(res, r);
    }

    use crate::backends::windows::filters::camel_case;
    use crate::ir::technique;
    use crate::ir::value::Expression;

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

    #[test]
    fn it_renders_parameters() {
        let t_id = "technique_id";
        let t_params = vec![technique::Parameter {
            name: "param1".to_string(),
            description: None,
            documentation: None,
            id: technique::Id::from_str("param_id").unwrap(),
            _type: technique::ParameterType::String,
            constraints: technique::Constraints {
                allow_empty: false,
                regex: None,
                select: None,
                password_hashes: None,
            },
            default: None,
        }];
        // Basic case with plain text
        let m_param = (
            "method_param_name".to_string(),
            "a simple test".to_string(),
            Escaping::String,
        );
        assert_eq!(
            "@'
a simple test
'@",
            parameter_fmt_stub(&&m_param, &t_id, &t_params).unwrap()
        );

        // Basic case with a GenericVar
        let m_param = (
            "method_param_name".to_string(),
            "a less simple ${plouf.plouf} test".to_string(),
            Escaping::String,
        );
        assert_eq!(
            "@'
a less simple 
'@ + ([Rudder.Datastate]::Render('{{{' + @'
vars.plouf.plouf
'@ + '}}}')) + @'
 test
'@",
            parameter_fmt_stub(&&m_param, &t_id, &t_params).unwrap()
        );

        // Basic case with a call to a short param name
        let m_param = (
            "method_param_name".to_string(),
            "a less simple ${param1} test".to_string(),
            Escaping::String,
        );
        assert_eq!(
            "@'
a less simple 
'@ + ([Rudder.Datastate]::Render('{{{' + @'
vars.technique_id.param1
'@ + '}}}')) + @'
 test
'@",
            parameter_fmt_stub(&&m_param, &t_id, &t_params).unwrap()
        );

        // Complex case with a Generic looking like a technique param
        let m_param = (
            "method_param_name".to_string(),
            "a less simple ${plouf.param1} test".to_string(),
            Escaping::String,
        );
        assert_eq!(
            "@'
a less simple 
'@ + ([Rudder.Datastate]::Render('{{{' + @'
vars.plouf.param1
'@ + '}}}')) + @'
 test
'@",
            parameter_fmt_stub(&&m_param, &t_id, &t_params).unwrap()
        );

        // With a Generic var looking like a technique param
        let m_param = (
            "method_param_name".to_string(),
            "a less simple ${param1or2} test".to_string(),
            Escaping::String,
        );
        assert_eq!(
            "@'
a less simple 
'@ + ([Rudder.Datastate]::Render('{{{' + @'
vars.param1or2
'@ + '}}}')) + @'
 test
'@",
            parameter_fmt_stub(&&m_param, &t_id, &t_params).unwrap()
        );

        // With a sys variable
        let m_param = (
            "method_param_name".to_string(),
            "a less simple ${sys.host} test".to_string(),
            Escaping::String,
        );
        assert_eq!(
            "@'
a less simple 
'@ + ([Rudder.Datastate]::Render('{{{' + @'
vars.sys.host
'@ + '}}}')) + @'
 test
'@",
            parameter_fmt_stub(&&m_param, &t_id, &t_params).unwrap()
        );

        // With a const variable
        let m_param = (
            "method_param_name".to_string(),
            "a less simple ${const.n} test".to_string(),
            Escaping::String,
        );
        assert_eq!(
            "@'
a less simple 
'@ + ([Rudder.Datastate]::Render('{{{' + @'
vars.const.n
'@ + '}}}')) + @'
 test
'@",
            parameter_fmt_stub(&&m_param, &t_id, &t_params).unwrap()
        );
    }
}
