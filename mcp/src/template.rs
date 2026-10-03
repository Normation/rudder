//! Template rendering with the minijinja engine of the template module.
//!
//! Templates and data come from the model, so rendering is always sandboxed. The other engines of
//! the module are not offered:
//!
//! - Mustache ignores the sandboxed mode: `{{> name}}` partials read `<name>.mustache` files from
//!   the working directory (see `engine/mustache.rs` in the module).
//! - Jinja2 runs a Python process per render and needs Python with `jinja2` on the server.

use rudder_module_template::engine::{Engine, Mode};
use serde_json::Value;

/// Renders `template` with `data`, as the template module would on a node.
///
/// Rendering is CPU-bound, so it runs on the blocking thread pool to keep the async workers free.
pub async fn render(template: String, data: Value) -> Result<String, String> {
    // Spans do not cross threads by themselves: keep the request's (account id) in logs
    let span = tracing::Span::current();
    tokio::task::spawn_blocking(move || {
        let _span = span.enter();
        Engine::Minijinja
            .renderer(None)?
            .render(None, Some(&template), &data, Mode::Sandboxed)
    })
    .await
    .map_err(|e| format!("rendering was aborted: {e}"))?
    // `{:#}` keeps the whole error chain, including the template error location
    .map_err(|e| format!("{e:#}"))
}

#[cfg(test)]
mod tests {
    use pretty_assertions::assert_eq;
    use serde_json::json;

    use super::*;

    #[tokio::test]
    async fn renders_with_module_behaviour() {
        let output = render(
            "server {{ name }};\n{% for p in ports %}listen {{ p }};\n{% endfor %}{{ name | b64encode }}"
                .to_owned(),
            json!({"name": "web", "ports": [80, 443]}),
        )
        .await
        .unwrap();
        assert_eq!(output, "server web;\nlisten 80;\nlisten 443;\nd2Vi");
    }

    #[tokio::test]
    async fn undefined_variable_is_an_error_with_location() {
        let error = render("a\nhello {{ nme }}".to_owned(), json!({"name": "x"}))
            .await
            .unwrap_err();
        assert!(error.contains("undefined value"), "{error}");
        assert!(error.contains("inline:2"), "{error}");
    }

    #[tokio::test]
    async fn sandboxed_no_file_access() {
        let error = render(
            r#"{{ lookup("file", "/etc/passwd") }}"#.to_owned(),
            json!({}),
        )
        .await
        .unwrap_err();
        assert!(error.contains("unknown function"), "{error}");
        let error = render(r#"{% include "/etc/passwd" %}"#.to_owned(), json!({}))
            .await
            .unwrap_err();
        assert!(error.contains("template not found"), "{error}");
    }
}
