// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

mod api;
mod auth;
mod cli;
mod config;
#[cfg(test)]
mod tests;

use std::{net::SocketAddr, process::ExitCode, sync::Arc};

use axum::{http::request::Parts, middleware};
use clap::Parser;
use rmcp::{
    ErrorData, ServerHandler,
    handler::server::tool::Extension,
    model::{ServerCapabilities, ServerConfig},
    tool, tool_handler, tool_router,
    transport::streamable_http_server::{
        StreamableHttpServerConfig, StreamableHttpService, session::never::NeverSessionManager,
    },
};

use tracing::{error, info};

use rudder_cli::logs::daemon;

use crate::{
    api::RudderApi,
    auth::{ApiAccount, require_token},
    cli::Cli,
    config::Config,
};

#[derive(Debug, Clone)]
struct Rudder;

#[tool_router]
impl Rudder {
    #[tool(
        description = "Who is calling: the Rudder API account behind the caller's token, and its rights",
        annotations(read_only_hint = true)
    )]
    fn whoami(&self, Extension(parts): Extension<Parts>) -> Result<String, ErrorData> {
        // Always inserted by `require_token`
        let account = parts
            .extensions
            .get::<ApiAccount>()
            .ok_or_else(|| ErrorData::internal_error("no account in request", None))?;
        Ok(format!(
            "Rudder API account: '{}' (id {})\nRights: {}",
            account.name,
            account.id,
            account.authorization_type.describe()
        ))
    }
}

#[tool_handler]
impl ServerHandler for Rudder {
    fn get_info(&self) -> ServerConfig {
        ServerConfig::new(ServerCapabilities::builder().enable_tools().build())
    }
}

/// The MCP endpoint, stateless: a `Rudder` is built for each request. Every request must carry a
/// Rudder API token, checked with `api`.
fn router(api: RudderApi) -> axum::Router {
    let service = StreamableHttpService::new(
        || Ok(Rudder),
        Arc::new(NeverSessionManager::default()),
        StreamableHttpServerConfig::default()
            .with_legacy_session_mode(false)
            .with_json_response(true),
    );
    axum::Router::new()
        .nest_service("/mcp", service)
        .layer(middleware::from_fn_with_state(api, require_token))
}

/// Serves the MCP endpoint until an error occurs
async fn serve(listener: tokio::net::TcpListener, api: RudderApi) -> std::io::Result<()> {
    // The client address is in the request logs
    let service = router(api).into_make_service_with_connect_info::<SocketAddr>();
    axum::serve(listener, service).await
}

/// Exit codes, as relayd: the unit does not restart on startup errors (`RestartPreventExitStatus`),
/// as a restart would not fix them. Other failures exit with 1 and are restarted.
const EXIT_CONFIG_ERROR: u8 = 2;
const EXIT_START_ERROR: u8 = 3;

#[tokio::main]
async fn main() -> ExitCode {
    let cli = Cli::parse();
    let log_handle = match daemon::init() {
        Ok(log_handle) => log_handle,
        Err(e) => {
            eprintln!("{e:?}");
            return ExitCode::from(EXIT_START_ERROR);
        }
    };

    let log_config = match config::load_log_config(&cli.config) {
        Ok(log_config) => log_config,
        Err(e) => return fail(&e, EXIT_CONFIG_ERROR),
    };
    if let Err(e) = log_handle.reload(&log_config) {
        return fail(&e, EXIT_START_ERROR);
    }
    let config = match Config::load(&cli.config) {
        Ok(config) => config,
        Err(e) => return fail(&e, EXIT_CONFIG_ERROR),
    };
    info!(
        "Starting {} {}",
        env!("CARGO_PKG_NAME"),
        env!("CARGO_PKG_VERSION")
    );
    info!("Read configuration from {}", cli.config.display());

    let api = match RudderApi::new(&config.api) {
        Ok(api) => api,
        Err(e) => return fail(&e, EXIT_START_ERROR),
    };
    info!("Using Rudder API at {}", api.url());

    let listen = config.server.listen;
    let listener = match tokio::net::TcpListener::bind(listen).await {
        Ok(listener) => listener,
        Err(e) => {
            let e = anyhow::Error::new(e).context(format!("could not listen on {listen}"));
            return fail(&e, EXIT_START_ERROR);
        }
    };
    info!("Listening on http://{listen}/mcp");
    if let Err(e) = serve(listener, api).await {
        return fail(&e.into(), 1);
    }
    ExitCode::SUCCESS
}

fn fail(error: &anyhow::Error, code: u8) -> ExitCode {
    // `{:?}` shows the cause chain
    error!("{error:?}");
    ExitCode::from(code)
}
