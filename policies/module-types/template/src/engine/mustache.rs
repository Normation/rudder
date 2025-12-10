// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021-2025 Normation SAS

use crate::engine::TemplateEngine;
use anyhow::{Context, Result};
use serde_json::Value;
use std::path::Path;

pub(crate) struct MustacheEngine;

impl TemplateEngine for MustacheEngine {
    fn render(
        &self,
        template_path: Option<&Path>,
        template_src: Option<&str>,
        data: Value,
    ) -> Result<String> {
        let template =
            match (&template_path, template_src) {
                (Some(p), _) => mustache::compile_path(p)
                    .with_context(|| "Failed to compile mustache template")?,
                (_, Some(s)) => mustache::compile_str(s)
                    .with_context(|| "Failed to compile mustache template")?,
                _ => unreachable!(),
            };
        template
            .render_to_string(&data)
            .with_context(|| "Rendering mustache template")
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn test_mustache_rendering() {
        let engine = MustacheEngine;

        let template = "Hello, {{ name }}!";
        let data = json!({ "name": "World" });

        let result = engine
            .render(None, Some(template), data)
            .expect("Rendering failed");

        assert_eq!(result, r#"Hello, World!"#);
    }

    #[test]
    fn test_mustache_rendering_cfengine_extension() {
        let engine = MustacheEngine;

        let template = "{{%-top-}}";
        let data = json!({ "items": ["A", "B", "C"] });
        let result = engine
            .render(None, Some(template), data)
            .expect("Rendering failed");
        assert_eq!(
            result,
            "{\n  \"items\": [\n    \"A\",\n    \"B\",\n    \"C\"\n  ]\n}"
        );
    }
}
