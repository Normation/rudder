// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

//! The MCP server and its tools, one module per tool

mod server_info;
mod whoami;

use axum::http::request::Parts;
use rmcp::{
    ServerHandler,
    handler::server::router::tool::ToolRouter,
    model::{ServerCapabilities, ServerConfig},
    tool_handler,
};

use crate::auth::Caller;

/// The MCP server, built for each request
#[derive(Debug, Clone)]
pub struct Rudder;

impl Rudder {
    /// Each tool module has its own router
    fn tool_router() -> ToolRouter<Self> {
        Self::whoami_router() + Self::server_info_router()
    }
}

#[tool_handler]
impl ServerHandler for Rudder {
    fn get_info(&self) -> ServerConfig {
        ServerConfig::new(ServerCapabilities::builder().enable_tools().build())
    }
}

/// The caller, always inserted by `require_token`
fn caller(parts: &Parts) -> Result<&Caller, String> {
    parts
        .extensions
        .get::<Caller>()
        .ok_or_else(|| "no caller in request".to_owned())
}

/// A failed tool call, shown to the model as the tool result: e.g. a 403 when the caller lacks
/// the right, so it can tell the user
fn tool_error(error: anyhow::Error) -> String {
    // `{:#}` shows the cause chain
    format!("{error:#}")
}
