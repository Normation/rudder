// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use super::Backend;
use crate::ir::Technique;
use std::path::Path;

use anyhow::Result;
use askama::Template;

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
#[template(path = "technique.ps1", escape = "none")]
struct TechniqueTemplate<'a> {
    id: &'a str,
    has_modules: bool,
    methods: Vec<Method>,
}

struct Method {
    index: String,
    class_prefix: String,
    component_name: String,
    component_key: String,
    disable_reporting: String,
    condition: Option<String>,
    args: String,
    name: String,
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
        let technique = TechniqueTemplate {
            id: &src.id.to_string(),
            has_modules: !Windows::list_resources(resources)?.is_empty(),
            // FIXME: add content
            methods: vec![],
        };
        technique.render().map_err(|e| e.into())
    }
}
