// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use std::path::Path;

use anyhow::{bail, Error, Result};
use askama::Template;

use super::Backend;
use crate::ir::{
    condition::Condition,
    technique::{ItemKind, LeafReporting, Method, Parameter},
    Technique,
};

pub struct Windows;

impl Default for Windows {
    fn default() -> Self {
        Self::new()
    }
}

impl Backend for Windows {
    fn generate(&self, technique: Technique, resources: &Path) -> Result<String> {
        Self::technique(technique, resources)
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

struct WindowsMethod {
    id: String,
    class_prefix: String,
    component_name: String,
    component_key: String,
    disable_reporting: bool,
    condition: Option<String>,
    args: Vec<(String, String)>,
    name: String,
}

impl TryFrom<Method> for WindowsMethod {
    type Error = Error;

    fn try_from(m: Method) -> Result<Self, Self::Error> {
        let Some(report_parameter) = m
            .params
            .get(&m.info.unwrap().class_parameter) else {
            bail!("Missing parameter {}", m.info.unwrap().class_parameter)
        };

        let mut args: Vec<(String, String)> = m.params.clone().into_iter().collect();
        // We want a stable output
        args.sort();

        Ok(Self {
            id: m.id.to_string(),
            class_prefix: m.info.as_ref().unwrap().class_prefix.clone(),
            component_name: m.name,
            component_key: report_parameter.to_string(),
            disable_reporting: m.reporting == LeafReporting::Disabled,
            // FIXME: None
            condition: Some(m.condition.to_string()),
            args,
            name: Windows::pascal_case(&m.info.as_ref().unwrap().bundle_name),
        })
    }
}

impl Windows {
    pub fn new() -> Self {
        Self
    }

    fn pascal_case(s: &str) -> String {
        let chars = s.chars();

        let mut pascal = String::new();
        let mut is_next_uppercase = true;
        for c in chars {
            let next = match c {
                ' ' | '_' | '-' => {
                    is_next_uppercase = true;
                    String::new()
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
            pascal.push_str(&next);
        }
        pascal
    }

    pub fn technique_name(s: &str) -> String {
        Self::pascal_case(s)
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
                    let method: Vec<WindowsMethod> = vec![r.try_into()?];
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
            parameters: src.parameters,
            methods,
        };
        technique.render().map_err(|e| e.into())
    }
}
