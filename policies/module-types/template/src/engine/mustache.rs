// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021-2025 Normation SAS

use crate::engine::{Mode, TemplateEngine};
use anyhow::{Context, Result};
use serde_json::Value;
use std::fs;
use std::path::Path;

pub(crate) struct MustacheEngine;

impl TemplateEngine for MustacheEngine {
    fn render(
        &self,
        template_path: Option<&Path>,
        template_string: Option<&str>,
        data: &Value,
        // FIXME: mode is not enforced. Mustache is logic-less, but `{{> name}}`
        // partials read `<name>.mustache` from disk (relative to the cwd here,
        // since we use `compile_str`), violating the sandboxed contract.
        // Sandboxed mode should reject templates containing partials.
        _mode: Mode,
    ) -> Result<String> {
        let template = match (template_path, template_string) {
            // The lib has a `compile_path` method, but it requires a mustache file extension.
            (Some(p), _) => &fs::read_to_string(p)
                .with_context(|| format!("Failed to read template {}", p.display()))?,
            (_, Some(s)) => s,
            _ => unreachable!(),
        };
        let compiled = mustache::compile_str(template)
            .with_context(|| "Failed to compile mustache template")?;
        compiled
            .render_to_string(data)
            .with_context(|| "Rendering mustache template")
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use std::fs;
    use tempfile::tempdir;

    #[test]
    fn test_mustache_rendering() {
        let engine = MustacheEngine;

        let template = "Hello, {{ name }}!";
        let data = json!({ "name": "World" });

        let result = engine
            .render(None, Some(template), &data, Mode::Sandboxed)
            .expect("Rendering failed");

        assert_eq!(result, r#"Hello, World!"#);
    }

    #[test]
    fn test_mustache_rendering_from_file() {
        let engine = MustacheEngine;

        let tmp_dir = tempdir().unwrap();
        let tmp_file = tmp_dir.path().join("template");

        let template = "Hello, {{ name }}!";
        fs::write(&tmp_file, template).unwrap();

        let data = json!({ "name": "World" });

        let result = engine
            .render(Some(tmp_file.as_path()), None, &data, Mode::Sandboxed)
            .expect("Rendering failed");

        assert_eq!(result, r#"Hello, World!"#);
    }

    #[test]
    fn test_mustache_rendering_cfengine_extension() {
        let engine = MustacheEngine;

        let template = "{{%-top-}}";
        let data = json!({ "items": ["A", "B", "C"] });
        let result = engine
            .render(None, Some(template), &data, Mode::Sandboxed)
            .expect("Rendering failed");
        assert_eq!(
            result,
            "{\n  \"items\": [\n    \"A\",\n    \"B\",\n    \"C\"\n  ]\n}"
        );
    }

    #[test]
    fn test_mustache_empty_line_end() {
        let engine = MustacheEngine;

        let template = "test\n";
        let data = json!({ "items": ["A", "B", "C"] });
        let result = engine
            .render(None, Some(template), &data, Mode::Sandboxed)
            .expect("Rendering failed");
        assert_eq!(result, "test\n");
    }
}
