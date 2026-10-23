// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

mod cli;
mod config;
#[cfg(test)]
mod tests;

use std::sync::Arc;

use anyhow::{Context, Result};
use clap::Parser;
use rmcp::{
    ServerHandler,
    model::{ServerCapabilities, ServerConfig},
    tool, tool_handler, tool_router,
    transport::streamable_http_server::{
        StreamableHttpServerConfig, StreamableHttpService, session::never::NeverSessionManager,
    },
};

use crate::{cli::Cli, config::Config};

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
async fn main() -> Result<()> {
    let cli = Cli::parse();
    let listen = Config::load(&cli.config)?.server.listen;
    let listener = tokio::net::TcpListener::bind(listen)
        .await
        .with_context(|| format!("could not listen on {listen}"))?;
    Ok(axum::serve(listener, router()).await?)
}
