// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

mod cli;
mod config;
#[cfg(test)]
mod tests;

use std::{process::ExitCode, sync::Arc};

use clap::Parser;
use rmcp::{
    ServerHandler,
    model::{ServerCapabilities, ServerConfig},
    tool, tool_handler, tool_router,
    transport::streamable_http_server::{
        StreamableHttpServerConfig, StreamableHttpService, session::never::NeverSessionManager,
    },
};

use tracing::{error, info};

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

/// Exit codes, as relayd: the unit does not restart on startup errors (`RestartPreventExitStatus`),
/// as a restart would not fix them. Other failures exit with 1 and are restarted.
const EXIT_CONFIG_ERROR: u8 = 2;
const EXIT_START_ERROR: u8 = 3;

#[tokio::main]
async fn main() -> ExitCode {
    let cli = Cli::parse();
    // As relayd: no timestamps as the journal adds them, only errors until the configuration is
    // loaded
    let logger = tracing_subscriber::fmt()
        .without_time()
        .with_env_filter("error")
        .with_filter_reloading();
    let log_filter = logger.reload_handle();
    logger.init();

    let config = match Config::load(&cli.config) {
        Ok(config) => config,
        Err(e) => return fail(&e, EXIT_CONFIG_ERROR),
    };
    if let Err(e) = log_filter.reload(config.logging.to_string()) {
        return fail(&e.into(), EXIT_START_ERROR);
    }
    info!(
        "Starting {} {}",
        env!("CARGO_PKG_NAME"),
        env!("CARGO_PKG_VERSION")
    );
    info!("Read configuration from {}", cli.config.display());

    let listen = config.server.listen;
    let listener = match tokio::net::TcpListener::bind(listen).await {
        Ok(listener) => listener,
        Err(e) => {
            let e = anyhow::Error::new(e).context(format!("could not listen on {listen}"));
            return fail(&e, EXIT_START_ERROR);
        }
    };
    info!("Listening on http://{listen}/mcp");
    if let Err(e) = axum::serve(listener, router()).await {
        return fail(&e.into(), 1);
    }
    ExitCode::SUCCESS
}

fn fail(error: &anyhow::Error, code: u8) -> ExitCode {
    // `{:?}` shows the cause chain
    error!("{error:?}");
    ExitCode::from(code)
}
