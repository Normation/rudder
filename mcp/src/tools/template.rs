// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

use rmcp::{handler::server::wrapper::Parameters, schemars, tool, tool_router};
use rudder_module_template::render_inline_minijinja;
use serde::Deserialize;
use serde_json::{Map, Value};

use super::{Rudder, tool_error};

#[derive(Debug, Deserialize, schemars::JsonSchema)]
#[serde(deny_unknown_fields)]
struct RenderTemplate {
    /// Inline MiniJinja template source
    template: String,
    /// Template variables as a JSON object
    data: Map<String, Value>,
}

#[tool_router(router = template_router, vis = "pub(super)")]
impl Rudder {
    #[tool(
        description = "Preview an inline MiniJinja template with the Rudder template module's filters and whitespace settings. Returns the rendered text or a template error. Variables come from the supplied JSON object.",
        annotations(read_only_hint = true)
    )]
    async fn render_template(
        &self,
        Parameters(RenderTemplate { template, data }): Parameters<RenderTemplate>,
    ) -> Result<String, String> {
        let span = tracing::Span::current();
        tokio::task::spawn_blocking(move || {
            span.in_scope(|| render_inline_minijinja(&template, &Value::Object(data)))
        })
        .await
        .map_err(|e| format!("rendering was aborted: {e}"))?
        .map_err(tool_error)
    }
}
