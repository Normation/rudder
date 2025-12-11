// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021-2025 Normation SAS

use crate::engine::TemplateEngine;
use anyhow::{Context, Result};
use minijinja::UndefinedBehavior;
use serde_json::Value;
use std::{fs::read_to_string, path::Path};

mod filters;

pub(crate) struct MiniJinjaEngine;

impl TemplateEngine for MiniJinjaEngine {
    fn render(
        &self,
        template_path: Option<&Path>,
        template_src: Option<&str>,
        data: Value,
    ) -> Result<String> {
        // We need to create the Environment even for one template
        let mut env = minijinja::Environment::new();
        // Fail on non-defined values, even in iteration
        env.set_undefined_behavior(UndefinedBehavior::Strict);
        // Remove line breaks after blocks. Ansible's default.
        env.set_trim_blocks(true);
        minijinja_contrib::add_to_environment(&mut env);

        let template_name = match (template_path, template_src) {
            (Some(p), _) => {
                let template = read_to_string(p)
                    .with_context(|| format!("Failed to read template {}", p.display()))?;
                let template_name = p.file_name().unwrap().to_string_lossy().into_owned();
                env.add_template_owned(template_name.clone(), template)?;
                template_name
            }
            (_, Some(s)) => {
                env.add_template("template", s)?;
                "template".to_string()
            }
            _ => unreachable!(),
        };

        env.add_filter("b64encode", filters::b64encode);
        env.add_filter("b64decode", filters::b64decode);
        env.add_filter("basename", filters::basename);
        env.add_filter("dirname", filters::dirname);
        env.add_filter("urldecode", filters::urldecode);
        env.add_filter("hash", filters::hash);
        env.add_filter("quote", filters::quote);
        env.add_filter("regex_escape", filters::regex_escape);
        env.add_filter("regex_replace", filters::regex_replace);
        let tmpl = env.get_template(&template_name)?;
        Ok(tmpl.render(data)?)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn test_minijinja_rendering() {
        let engine = MiniJinjaEngine;

        let template = "Hello, {{ name }}!";
        let data = json!({ "name": "World" });

        let result = engine
            .render(None, Some(template), data)
            .expect("Rendering failed");

        assert_eq!(result, r#"Hello, World!"#);
    }

    #[test]
    fn test_minijinja_rendering_trim_block() {
        let engine = MiniJinjaEngine;

        let template = "{% for item in items %}
Item: {{ item }}
{% endfor %}";
        let data = json!({ "items": ["A", "B", "C"] });
        let result = engine
            .render(None, Some(template), data)
            .expect("Rendering failed");
        assert_eq!(result, "Item: A\nItem: B\nItem: C\n");
    }
}
