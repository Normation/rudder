// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

//! The MCP server and its tools, one module per tool, plus what several share (`node`, `policy_mode`).
//!
//! Each tool's Rudder API calls and the rights they need are listed in `DESIGN.md` (Rudder
//! rights): update it with the tool.

mod list_nodes;
mod node;
mod node_compliance;
mod node_info;
mod node_policies;
mod policy_mode;
mod server_info;
mod whoami;

use anyhow::Context;
use axum::http::request::Parts;
use rmcp::{
    ErrorData, RoleServer, ServerHandler,
    handler::server::{router::tool::ToolRouter, tool::ToolCallContext},
    model::{
        CallToolRequestParams, CallToolResponse, CallToolResult, ContentBlock, ServerCapabilities,
        ServerConfig,
    },
    service::RequestContext,
    tool_handler,
};
use serde::de::DeserializeOwned;

use crate::{
    api::Envelope,
    auth::{self, Caller},
};

/// The MCP server, built for each request
#[derive(Debug, Clone)]
pub struct Rudder;

impl Rudder {
    /// Each tool module has its own router
    fn tool_router() -> ToolRouter<Self> {
        Self::whoami_router()
            + Self::server_info_router()
            + Self::node_info_router()
            + Self::node_policies_router()
            + Self::node_compliance_router()
            + Self::list_nodes_router()
    }
}

#[tool_handler]
impl ServerHandler for Rudder {
    fn get_info(&self) -> ServerConfig {
        ServerConfig::new(ServerCapabilities::builder().enable_tools().build())
    }

    /// Only tool calls are authenticated, before reaching the tool: a client can connect and list
    /// the tools without a token, or while Rudder is unreachable.
    async fn call_tool(
        &self,
        request: CallToolRequestParams,
        mut context: RequestContext<RoleServer>,
    ) -> Result<CallToolResponse, ErrorData> {
        let parts = context
            .extensions
            .get_mut::<Parts>()
            .ok_or_else(|| ErrorData::internal_error("no HTTP request", None))?;
        auth::record_tool_call(parts, &request.name);
        if let Err(e) = auth::authenticate(parts).await {
            return Ok(CallToolResult::error(vec![ContentBlock::text(e)]).into());
        }
        Self::tool_router()
            .call(ToolCallContext::new(self, request, context))
            .await
    }
}

/// The caller, always inserted by `auth::authenticate` before a tool runs
fn caller(parts: &Parts) -> Result<&Caller, String> {
    parts
        .extensions
        .get::<Caller>()
        .ok_or_else(|| "no caller in request".to_owned())
}

/// Context of the errors on a Rudder answer that does not have the expected shape: the API changed
const UNEXPECTED: &str = "unexpected Rudder API response";

/// The `data` of a Rudder API response body
fn data<T: DeserializeOwned>(body: &str) -> anyhow::Result<T> {
    Ok(serde_json::from_str::<Envelope<T>>(body)
        .context(UNEXPECTED)?
        .data)
}

/// A failed tool call, shown to the model as the tool result: e.g. a 403 when the caller lacks
/// the right, so it can tell the user
fn tool_error(error: anyhow::Error) -> String {
    // `{:#}` shows the cause chain
    format!("{error:#}")
}
