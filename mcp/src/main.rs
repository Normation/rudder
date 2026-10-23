// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

#[cfg(test)]
mod tests;

use std::sync::Arc;

use rmcp::{
    ServerHandler,
    model::{ServerCapabilities, ServerConfig},
    tool, tool_handler, tool_router,
    transport::streamable_http_server::{
        StreamableHttpServerConfig, StreamableHttpService, session::never::NeverSessionManager,
    },
};

#[derive(Debug, Clone)]
struct Rudder;

#[tool_router]
impl Rudder {
    #[tool(description = "Check that the Rudder MCP server answers")]
    fn hello(&self) -> String {
        "Hello from the Rudder MCP server".to_owned()
    }
}

#[tool_handler]
impl ServerHandler for Rudder {
    fn get_info(&self) -> ServerConfig {
        ServerConfig::new(ServerCapabilities::builder().enable_tools().build())
    }
}

/// The MCP endpoint, stateless: a `Rudder` is built for each request
fn router() -> axum::Router {
    let service = StreamableHttpService::new(
        || Ok(Rudder),
        Arc::new(NeverSessionManager::default()),
        StreamableHttpServerConfig::default()
            .with_legacy_session_mode(false)
            .with_json_response(true),
    );
    axum::Router::new().nest_service("/mcp", service)
}

#[tokio::main]
async fn main() -> std::io::Result<()> {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:8000").await?;
    axum::serve(listener, router()).await
}
