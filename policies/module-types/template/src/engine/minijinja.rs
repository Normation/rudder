// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021-2025 Normation SAS

use crate::engine::{Mode, TemplateEngine};
use anyhow::{Context, Result, anyhow};
use minijinja::UndefinedBehavior;
use rudder_module_type::cli::{FileError, FileRange};
use serde_json::Value;
use std::{fs::read_to_string, path::Path};

mod filters;

/// Fuel budget: caps the operations a single render may perform, to
/// stop runaway templates (e.g. huge loops) from untrusted content. Deliberately
/// high so legitimate templates are never affected.
///
/// At this value a tight runaway loop is aborted after ~1.3s (release),
/// but it does not cap memory.
const SANDBOX_FUEL: u64 = 100_000_000;

/// Prevent accidental DoS in a trusted template, while staying well out of
/// the way of legitimate heavy templates.
const UNRESTRICTED_FUEL: u64 = 1_000_000_000;

pub(crate) struct MiniJinjaEngine {
    sandbox_fuel: u64,
    unrestricted_fuel: u64,
}

impl Default for MiniJinjaEngine {
    fn default() -> Self {
        Self {
            sandbox_fuel: SANDBOX_FUEL,
            unrestricted_fuel: UNRESTRICTED_FUEL,
        }
    }
}

impl MiniJinjaEngine {
    fn error_formatting(e: minijinja::Error, template: &str, template_name: &str) -> anyhow::Error {
        if let Some(r) = e.range() {
            let e_kind = e.kind().to_string();
            let err = FileError::new(
                "Could not render template",
                &e_kind,
                FileRange::Byte(r),
                template_name,
                template,
                e.detail(),
            );
            anyhow!(err.render(None))
        } else {
            e.into()
        }
    }
}

impl TemplateEngine for MiniJinjaEngine {
    fn render(
        &self,
        template_path: Option<&Path>,
        template_string: Option<&str>,
        data: &Value,
        mode: Mode,
    ) -> Result<String> {
        // We need to create the Environment even for one template
        let mut env = minijinja::Environment::new();
        // Fail on non-defined values, even in iteration
        env.set_undefined_behavior(UndefinedBehavior::Strict);
        // Remove line breaks after blocks. Ansible's default.
        env.set_trim_blocks(true);
        env.set_keep_trailing_newline(true);
        minijinja_contrib::add_to_environment(&mut env);
        // To get detailed error messages on template compilation
        env.set_debug(true);
        // Bound compute in both modes; unrestricted gets a much larger budget.
        let fuel = if mode.is_sandboxed() {
            self.sandbox_fuel
        } else {
            self.unrestricted_fuel
        };
        env.set_fuel(Some(fuel));

        let (template_name, template) = match (template_path, template_string) {
            (Some(p), _) => {
                let template = read_to_string(p)
                    .with_context(|| format!("Failed to read template {}", p.display()))?;
                let template_name = p.file_name().unwrap().to_string_lossy().into_owned();
                (template_name, template)
            }
            (_, Some(s)) => ("inline".to_string(), s.to_string()),
            _ => unreachable!(),
        };

        env.add_template(&template_name, &template)?;
        env.add_filter("b64encode", filters::b64encode);
        env.add_filter("b64decode", filters::b64decode);
        env.add_filter("basename", filters::basename);
        env.add_filter("dirname", filters::dirname);
        env.add_filter("urldecode", filters::urldecode);
        env.add_filter("hash", filters::hash);
        env.add_filter("quote", filters::quote);
        env.add_filter("regex_escape", filters::regex_escape);
        env.add_filter("regex_replace", filters::regex_replace);
        // Restrict FS access
        if !mode.is_sandboxed() {
            env.add_function("lookup", filters::lookup);
            // Resolve included templates relative to the source template's dir.
            // Inline templates have no base dir.
            if let Some(dir) = template_path.and_then(Path::parent) {
                env.set_loader(minijinja::path_loader(dir));
            }
        }
        let tmpl = env.get_template(&template_name)?;
        tmpl.render(data)
            .map_err(|e| Self::error_formatting(e, &template, &template_name))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use std::fs;
    use tempfile::tempdir;

    #[test]
    fn test_minijinja_rendering() {
        let engine = MiniJinjaEngine::default();

        let template = "Hello, {{ name }}!";
        let data = json!({ "name": "World" });

        let result = engine
            .render(None, Some(template), &data, Mode::Sandboxed)
            .expect("Rendering failed");

        assert_eq!(result, r#"Hello, World!"#);
    }

    #[test]
    fn test_minijinja_rendering_from_file() {
        let engine = MiniJinjaEngine::default();

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
    fn test_minijinja_rendering_trim_block() {
        let engine = MiniJinjaEngine::default();

        let template = "{% for item in items %}
Item: {{ item }}
{% endfor %}";
        let data = json!({ "items": ["A", "B", "C"] });
        let result = engine
            .render(None, Some(template), &data, Mode::Sandboxed)
            .expect("Rendering failed");
        assert_eq!(result, "Item: A\nItem: B\nItem: C\n");
    }

    #[test]
    fn test_minijinja_empty_line_end() {
        let engine = MiniJinjaEngine::default();

        let template = "test\n";
        let data = json!({ "items": ["A", "B", "C"] });
        let result = engine
            .render(None, Some(template), &data, Mode::Sandboxed)
            .expect("Rendering failed");
        assert_eq!(result, "test\n");
    }

    #[test]
    fn test_minijinja_lookup_available_when_unrestricted() {
        let engine = MiniJinjaEngine::default();

        let dir = tempdir().unwrap();
        let source = dir.path().join("source.txt");
        fs::write(&source, "secret content").unwrap();

        // Pass the path through data so it is not parsed as a template literal
        // (a Windows path would contain invalid string escapes).
        let data = json!({ "p": source });
        let result = engine
            .render(
                None,
                Some("{{ lookup('file', p) }}"),
                &data,
                Mode::Unrestricted,
            )
            .expect("Rendering failed");

        assert_eq!(result, "secret content");
    }

    #[test]
    fn test_minijinja_lookup_blocked_when_sandboxed() {
        let engine = MiniJinjaEngine::default();

        let dir = tempdir().unwrap();
        let source = dir.path().join("source.txt");
        fs::write(&source, "secret content").unwrap();

        // `lookup` is not registered, so the unknown function fails the render.
        let data = json!({ "p": source });
        let result = engine.render(
            None,
            Some("{{ lookup('file', p) }}"),
            &data,
            Mode::Sandboxed,
        );

        assert!(result.is_err());
    }

    #[test]
    fn test_minijinja_include_available_when_unrestricted() {
        let engine = MiniJinjaEngine::default();

        let dir = tempdir().unwrap();
        fs::write(dir.path().join("included.txt"), "MIDDLE").unwrap();
        let main = dir.path().join("main.txt");
        fs::write(&main, r#"Start {% include "included.txt" %} End"#).unwrap();

        let result = engine
            .render(Some(main.as_path()), None, &json!({}), Mode::Unrestricted)
            .expect("Rendering failed");

        assert_eq!(result, "Start MIDDLE End");
    }

    #[test]
    fn test_minijinja_include_blocked_when_sandboxed() {
        let engine = MiniJinjaEngine::default();

        let dir = tempdir().unwrap();
        fs::write(dir.path().join("included.txt"), "MIDDLE").unwrap();
        let main = dir.path().join("main.txt");
        fs::write(&main, r#"Start {% include "included.txt" %} End"#).unwrap();

        // No loader in sandboxed mode: the include cannot be resolved.
        let result = engine.render(Some(main.as_path()), None, &json!({}), Mode::Sandboxed);

        assert!(result.is_err());
    }

    #[test]
    fn test_minijinja_filters_available_when_sandboxed() {
        let engine = MiniJinjaEngine::default();

        // Sandboxing must not disable the regular filters.
        let template = "{{ 'Hello, Ferris!' | b64encode }}";
        let result = engine
            .render(None, Some(template), &json!({}), Mode::Sandboxed)
            .expect("Rendering failed");

        assert_eq!(result, "SGVsbG8sIEZlcnJpcyE=");
    }

    #[test]
    fn test_minijinja_fuel_limits_runaway_when_sandboxed() {
        // Tiny fuel budget so the loop runs out almost immediately.
        let engine = MiniJinjaEngine {
            sandbox_fuel: 100,
            ..Default::default()
        };

        let template = "{% for i in range(1000000) %}{{ i }}{% endfor %}";
        let result = engine.render(None, Some(template), &json!({}), Mode::Sandboxed);

        assert!(result.is_err());
    }

    #[test]
    fn test_minijinja_unrestricted_allows_more_fuel_than_sandboxed() {
        // range(700) costs ~3500 fuel (~5/iteration): above the sandbox budget
        // but well under the unrestricted one.
        let engine = MiniJinjaEngine {
            sandbox_fuel: 1000,
            unrestricted_fuel: 10_000,
        };
        let template = "{% for i in range(700) %}{{ i }}{% endfor %}";

        assert!(
            engine
                .render(None, Some(template), &json!({}), Mode::Sandboxed)
                .is_err()
        );
        assert!(
            engine
                .render(None, Some(template), &json!({}), Mode::Unrestricted)
                .is_ok()
        );
    }

    #[test]
    fn test_minijinja_fuel_bounds_unrestricted_too() {
        // Even the unrestricted budget is far exceeded here.
        let engine = MiniJinjaEngine {
            sandbox_fuel: 100,
            unrestricted_fuel: 1000,
        };

        let template = "{% for i in range(1000000) %}{{ i }}{% endfor %}";
        let result = engine.render(None, Some(template), &json!({}), Mode::Unrestricted);

        assert!(result.is_err());
    }

    #[test]
    fn test_minijinja_loop_controls() {
        let engine = MiniJinjaEngine::default();

        // `break`/`continue` require the `loop_controls` feature.
        let template =
            "{% for i in range(10) %}{% if i == 3 %}{% break %}{% endif %}{{ i }}{% endfor %}";
        let result = engine
            .render(None, Some(template), &json!({}), Mode::Sandboxed)
            .expect("Rendering failed");

        assert_eq!(result, "012");
    }
}
