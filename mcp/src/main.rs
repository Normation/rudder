mod auth;
mod compliance;
mod config;
mod docs;
mod nodes;
mod rudderc;
mod rules;
mod system;
mod template;
#[cfg(test)]
mod tests;

use std::{borrow::Cow, process::ExitCode, sync::Arc, time::Duration};

use anyhow::{Context, Result};
use axum::{
    http::{HeaderValue, Method, request::Parts},
    middleware,
};
use clap::Parser;
use rmcp::{
    ServerHandler,
    handler::server::{
        router::{prompt::PromptRouter, tool::ToolRouter},
        tool::Extension,
        wrapper::Parameters,
    },
    model::{PromptMessage, ProtocolVersion, Role, ServerCapabilities, ServerConfig},
    prompt, prompt_handler, prompt_router, schemars, tool, tool_handler, tool_router,
    transport::streamable_http_server::{
        StreamableHttpServerConfig, StreamableHttpService, session::never::NeverSessionManager,
    },
};
use secrecy::ExposeSecret;
use serde::Deserialize;
use tokio::signal::unix::{SignalKind, signal};
use tracing_subscriber::{self, EnvFilter};

use crate::{
    auth::{ApiAccount, ApiToken, AuthorizationType, require_token},
    config::{ApiConfig, Cli, Config},
    docs::{DocTopic, TECHNIQUE_SYNTAX},
    nodes::InventorySection,
    rudderc::{MethodSummary, Rudderc},
};

/// Header Rudder reads the API token from
const API_TOKEN_HEADER: &str = "X-API-Token";
/// "Write technique" scenario, `{goal}` and `{syntax}` are replaced when the prompt is requested
const WRITE_TECHNIQUE_PROMPT: &str = include_str!("write_technique.md");
/// How the tools fit together, sent to clients in `server/discover`
const INSTRUCTIONS: &str = include_str!("instructions.md");
/// Bounds each Rudder API call, so a stuck call cannot hold a request (or shutdown) forever
const API_TIMEOUT: Duration = Duration::from_secs(30);

/// Which tools the server exposes
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum AccessMode {
    ReadOnly,
    ReadWrite,
}

impl From<bool> for AccessMode {
    fn from(read_only: bool) -> Self {
        if read_only {
            Self::ReadOnly
        } else {
            Self::ReadWrite
        }
    }
}

/// A Rudder object id (rule, directive, group) that can go into a URL path. System objects use ids
/// like `hasPolicyServer-root`, not only UUIDs.
fn is_object_id(id: &str) -> bool {
    !id.is_empty()
        && id
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
}

/// Client for the Rudder REST API
#[derive(Debug, Clone)]
struct RudderApi {
    client: reqwest::Client,
    /// Base URL, without trailing `/`
    url: Arc<str>,
}

impl RudderApi {
    fn new(config: &ApiConfig) -> Result<Self> {
        if config.tls_skip_verify {
            tracing::warn!(
                "TLS certificate verification disabled for the Rudder API (api.tls_skip_verify): forwarded tokens can be intercepted"
            );
        }
        Ok(Self {
            client: reqwest::Client::builder()
                .timeout(API_TIMEOUT)
                .danger_accept_invalid_certs(config.tls_skip_verify)
                .build()?,
            url: config.url.as_str().into(),
        })
    }

    async fn send(
        &self,
        token: &ApiToken,
        method: Method,
        path: &str,
        query: &[(&str, &str)],
    ) -> Result<reqwest::Response, String> {
        let mut token = HeaderValue::from_str(token.0.expose_secret())
            .map_err(|_| "API token is not a valid header value")?;
        token.set_sensitive(true);
        tracing::debug!(%method, path, "Rudder API call");
        self.client
            .request(method, format!("{}/{path}", self.url))
            .query(query)
            .header(API_TOKEN_HEADER, token)
            .send()
            .await
            // reqwest's message leaves out the cause (e.g. a rejected certificate): add the chain
            .map_err(|e| {
                let mut message = format!("Rudder API unreachable: {e}");
                let mut source = std::error::Error::source(&e);
                while let Some(cause) = source {
                    message.push_str(&format!(": {cause}"));
                    source = cause.source();
                }
                message
            })
    }

    /// Calls the Rudder API with the caller's token. The error is shown to the model as is.
    async fn call(
        &self,
        parts: &Parts,
        method: Method,
        path: &str,
        query: &[(&str, &str)],
    ) -> Result<String, String> {
        // Always inserted by `require_token`
        let token = parts
            .extensions
            .get::<ApiToken>()
            .ok_or("no API token in request")?;
        let response = self.send(token, method, path, query).await?;
        let status = response.status();
        let body = response
            .text()
            .await
            .map_err(|e| format!("Rudder API response could not be read: {e}"))?;
        if status.is_success() {
            Ok(body)
        } else {
            Err(format!("Rudder API returned {status}: {body}"))
        }
    }
}

#[derive(Debug, Deserialize, schemars::JsonSchema)]
#[serde(deny_unknown_fields)]
struct NodeQuery {
    /// Node id (UUID, or `root` for the Rudder server), or hostname exactly as in the inventory
    node: String,
    /// Inventory sections to add to the default information. Some can be large (software,
    /// processes): only request what is needed.
    #[serde(default)]
    details: Vec<InventorySection>,
}

#[derive(Debug, Deserialize, schemars::JsonSchema)]
#[serde(deny_unknown_fields)]
struct ComplianceQuery {
    /// Node id or exact hostname: compliance of this node, by rule
    node: Option<String>,
    /// Rule id: compliance of this rule, by directive and node
    rule: Option<String>,
}

#[derive(Debug, Deserialize, schemars::JsonSchema)]
#[serde(deny_unknown_fields)]
struct RuleQuery {
    /// Rule id (as listed by the `compliance` tool)
    rule: String,
}

#[derive(Debug, Deserialize, schemars::JsonSchema)]
struct CompileTechnique {
    /// Technique source, in Rudder's YAML technique format
    technique: String,
}

#[derive(Debug, Deserialize, schemars::JsonSchema)]
struct MethodQuery {
    /// Method id, as used in the `method` field of a technique (e.g. `package_present`). Without
    /// it, all methods are listed.
    method: Option<String>,
}

#[derive(Debug, Deserialize, schemars::JsonSchema)]
// Rejects e.g. an `engine` argument instead of silently rendering with minijinja
#[serde(deny_unknown_fields)]
struct RenderTemplate {
    /// Template source, in minijinja (Jinja2-compatible) syntax
    template: String,
    /// Data available to the template, as a JSON object
    data: serde_json::Map<String, serde_json::Value>,
}

#[derive(Debug, Deserialize, schemars::JsonSchema)]
struct DocumentationQuery {
    /// Documentation topic
    topic: DocTopic,
}

#[derive(Debug, Deserialize, schemars::JsonSchema)]
struct WriteTechnique {
    /// What the technique must achieve on the nodes
    goal: String,
}

#[derive(Debug, Clone)]
struct Rudder {
    api: RudderApi,
    rudderc: Rudderc,
    mode: AccessMode,
    // Built once at startup: a `Rudder` is created for every request
    tool_router: Arc<ToolRouter<Self>>,
    prompt_router: Arc<PromptRouter<Self>>,
}

impl Rudder {
    fn router(mode: AccessMode) -> Arc<ToolRouter<Self>> {
        let mut router = Self::read_router();
        if mode == AccessMode::ReadWrite {
            router += Self::write_router();
        }
        Arc::new(router)
    }
}

#[prompt_router]
impl Rudder {
    #[prompt(
        name = "write_technique",
        description = "Write a Rudder technique for a goal, step by step: pick methods, draft, compile until clean, check templates"
    )]
    async fn write_technique(
        &self,
        Parameters(WriteTechnique { goal }): Parameters<WriteTechnique>,
    ) -> Vec<PromptMessage> {
        let text = WRITE_TECHNIQUE_PROMPT
            .replace("{syntax}", TECHNIQUE_SYNTAX)
            .replace("{goal}", &goal);
        vec![PromptMessage::new_text(Role::User, text)]
    }
}

#[tool_router(router = read_router)]
impl Rudder {
    #[tool(
        description = "Who is calling: the Rudder API account behind the caller's token, its rights, and whether this MCP server offers write tools",
        annotations(read_only_hint = true)
    )]
    fn whoami(&self, Extension(parts): Extension<Parts>) -> String {
        // Always inserted by `require_token`
        let Some(account) = parts.extensions.get::<ApiAccount>() else {
            return "no account in request".to_owned();
        };
        let rights = account
            .authorization_type
            .map_or("unknown", AuthorizationType::describe);
        let writes = match self.mode {
            AccessMode::ReadOnly => "disabled",
            AccessMode::ReadWrite => "enabled",
        };
        format!(
            "Rudder API account: '{}' (id {})\nRights: {rights}\nWrite tools on this MCP server: {writes}",
            account.name, account.id
        )
    }

    #[tool(
        description = "Get a node's information from its inventory, by id or hostname: OS, IP addresses, last run and inventory dates, agent, policy mode, node properties, RAM. Other inventory sections on request",
        annotations(read_only_hint = true)
    )]
    async fn node_info(
        &self,
        Extension(parts): Extension<Parts>,
        Parameters(NodeQuery { node, details }): Parameters<NodeQuery>,
    ) -> Result<String, String> {
        let (path, query) = nodes::request(&node, &details);
        let query: Vec<(&str, &str)> = query.iter().map(|(k, v)| (*k, v.as_str())).collect();
        let body = self.api.call(&parts, Method::GET, &path, &query).await?;
        nodes::select(&body, &node)
    }

    #[tool(
        description = "Compliance: without arguments, global compliance and every rule's compliance (worst first). With `node` (id or hostname) or `rule` (id), the detail down to the failing components, with the agent report messages; compliant parts are only counted",
        annotations(read_only_hint = true)
    )]
    async fn compliance(
        &self,
        Extension(parts): Extension<Parts>,
        Parameters(ComplianceQuery { node, rule }): Parameters<ComplianceQuery>,
    ) -> Result<String, String> {
        match (node, rule) {
            (Some(_), Some(_)) => Err("give either `node` or `rule`, not both".to_owned()),
            (Some(node), None) => {
                let id = nodes::resolve_id(&self.api, &parts, &node).await?;
                let path = format!("compliance/nodes/{id}");
                let body = self.api.call(&parts, Method::GET, &path, &[]).await?;
                compliance::tree(&body, "nodes")
            }
            (None, Some(rule)) => {
                if !is_object_id(&rule) {
                    return Err(format!("'{rule}' is not a rule id"));
                }
                let path = format!("compliance/rules/{rule}");
                let body = self.api.call(&parts, Method::GET, &path, &[]).await?;
                compliance::tree(&body, "rules")
            }
            (None, None) => {
                let (global, rules) = tokio::join!(
                    self.api.call(&parts, Method::GET, "compliance", &[]),
                    self.api
                        .call(&parts, Method::GET, "compliance/rules", &[("level", "1")]),
                );
                compliance::global(&global?, &rules?)
            }
        }
    }

    #[tool(
        description = "A rule's definition: status (e.g. applied or not, and why), enabled, policy mode, description, its directives (technique, version, policy mode) and its targets (groups with their node count, or built-in targets)",
        annotations(read_only_hint = true)
    )]
    async fn rule_info(
        &self,
        Extension(parts): Extension<Parts>,
        Parameters(RuleQuery { rule }): Parameters<RuleQuery>,
    ) -> Result<String, String> {
        if !is_object_id(&rule) {
            return Err(format!("'{rule}' is not a rule id"));
        }
        rules::info(&self.api, &parts, &rule).await
    }

    #[tool(
        description = "Get Rudder server information: Rudder version, instance id, relays, OS, JVM version, node counts by policy mode, installed plugins",
        annotations(read_only_hint = true)
    )]
    async fn system_info(&self, Extension(parts): Extension<Parts>) -> Result<String, String> {
        let body = self
            .api
            .call(&parts, Method::GET, "system/info", &[])
            .await?;
        system::info(&body)
    }

    #[tool(
        description = "Check the Rudder server health: webapp status, and the server healthchecks (CPU, free disk space, file descriptors...) with their status and message",
        annotations(read_only_hint = true)
    )]
    async fn server_health(&self, Extension(parts): Extension<Parts>) -> Result<String, String> {
        let (status, checks) = tokio::join!(
            self.api.call(&parts, Method::GET, "system/status", &[]),
            self.api
                .call(&parts, Method::GET, "system/healthcheck", &[]),
        );
        system::health(&status?, &checks?)
    }

    #[tool(
        description = "Compile a YAML technique with rudderc. Returns rudderc's output and the generated files (technique.cf for Linux, technique.ps1 for Windows, metadata.xml), or the compilation errors",
        annotations(read_only_hint = true)
    )]
    async fn compile_technique(
        &self,
        Parameters(CompileTechnique { technique }): Parameters<CompileTechnique>,
    ) -> Result<String, String> {
        self.rudderc.build(&technique).await
    }

    #[tool(
        description = "Rudder documentation for writing techniques: the technique YAML format, an example technique, and the modules (template, augeas, commands, system updates, secedit)",
        annotations(read_only_hint = true)
    )]
    fn documentation(
        &self,
        Parameters(DocumentationQuery { topic }): Parameters<DocumentationQuery>,
    ) -> String {
        topic.content().to_owned()
    }

    #[tool(
        description = "Methods available in techniques. Without `method`: one line per method (id, name, description). With `method`: its full metadata as JSON (parameters with their constraints, supported agents, documentation)",
        annotations(read_only_hint = true)
    )]
    async fn methods(
        &self,
        Parameters(MethodQuery { method }): Parameters<MethodQuery>,
    ) -> Result<String, String> {
        let mut methods = self.rudderc.methods().await?;
        match method {
            Some(id) => {
                let mut info = methods.remove(&id).ok_or_else(|| {
                    format!("unknown method '{id}', call without `method` to list them")
                })?;
                // Path of the method on this server, meaningless to the client
                if let Some(info) = info.as_object_mut() {
                    info.remove("source");
                }
                serde_json::to_string_pretty(&info).map_err(|e| e.to_string())
            }
            None => {
                let mut list = String::new();
                for (id, info) in methods {
                    let summary = MethodSummary::deserialize(info)
                        .map_err(|e| format!("unexpected metadata for method '{id}': {e}"))?;
                    list.push_str(&format!("{id}: {}. {}", summary.name, summary.description));
                    if let Some(deprecated) = summary.deprecated {
                        list.push_str(&format!(" [deprecated: {deprecated}]"));
                    }
                    list.push('\n');
                }
                Ok(list)
            }
        }
    }

    #[tool(
        description = "Render a minijinja (Jinja2-compatible) template with the Rudder template module (sandboxed), as it would be on a node. Only the minijinja engine is supported. Returns the rendered text or the template error with its location",
        annotations(read_only_hint = true)
    )]
    async fn render_template(
        &self,
        Parameters(RenderTemplate { template, data }): Parameters<RenderTemplate>,
    ) -> Result<String, String> {
        template::render(template, data.into()).await
    }
}

#[tool_router(router = write_router)]
impl Rudder {
    #[tool(
        description = "Recompute the content of all dynamic node groups",
        annotations(read_only_hint = false, destructive_hint = false)
    )]
    async fn reload_groups(&self, Extension(parts): Extension<Parts>) -> Result<String, String> {
        self.api
            .call(&parts, Method::POST, "system/reload/groups", &[])
            .await
    }
}

#[tool_handler(router = self.tool_router)]
#[prompt_handler(router = self.prompt_router)]
impl ServerHandler for Rudder {
    fn get_info(&self) -> ServerConfig {
        // rmcp falls back to this version on legacy `initialize` without checking it against
        // `supported_protocol_versions`, so the default (2025-11-25) must not be left here
        ServerConfig::new(
            ServerCapabilities::builder()
                .enable_tools()
                .enable_prompts()
                .build(),
        )
        .with_protocol_version(ProtocolVersion::V_2026_07_28)
        .with_instructions(INSTRUCTIONS)
    }

    // Only the stateless protocol (SEP-2567), no session-based versions
    fn supported_protocol_versions(&self) -> Cow<'static, [ProtocolVersion]> {
        Cow::Borrowed(&[ProtocolVersion::V_2026_07_28])
    }
}

/// Exit codes, as `relayd`: the unit does not restart on startup errors (`RestartPreventExitStatus`),
/// as a restart would not fix them. Other failures exit with 1 and are restarted.
const EXIT_CONFIG_ERROR: u8 = 2;
const EXIT_START_ERROR: u8 = 3;

#[tokio::main]
async fn main() -> ExitCode {
    // `RUST_LOG` overrides the default level
    let filter = EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info"));
    let logs = tracing_subscriber::fmt()
        .with_env_filter(filter)
        .with_ansi(false);
    // systemd sets `JOURNAL_STREAM` when stdout goes to the journal, which timestamps lines itself
    if std::env::var_os("JOURNAL_STREAM").is_some() {
        logs.without_time().init();
    } else {
        logs.init();
    }

    let cli = Cli::parse();
    let config = match Config::load(&cli.config) {
        Ok(config) => config,
        Err(e) => return fail(&e, EXIT_CONFIG_ERROR),
    };
    tracing::info!("Using configuration file {}", cli.config.display());
    let (listener, router) = match start(config).await {
        Ok(server) => server,
        Err(e) => return fail(&e, EXIT_START_ERROR),
    };

    // In-flight requests are allowed to finish, systemd's `TimeoutStopSec` bounds the wait
    if let Err(e) = axum::serve(listener, router)
        .with_graceful_shutdown(shutdown_signal())
        .await
    {
        return fail(&e.into(), 1);
    }
    tracing::info!("MCP server stopped");
    ExitCode::SUCCESS
}

fn fail(error: &anyhow::Error, code: u8) -> ExitCode {
    // `{:?}` shows the cause chain, including the location of a configuration error
    tracing::error!("{error:?}");
    ExitCode::from(code)
}

/// Builds the server from its configuration and binds the listen address
async fn start(config: Config) -> Result<(tokio::net::TcpListener, axum::Router)> {
    let listen = config.server.listen;
    let mode = AccessMode::from(config.server.read_only);
    let api = RudderApi::new(&config.api)?;
    tracing::info!("Using Rudder API at {}", api.url);
    let rudderc = Rudderc::new(config.rudderc.path);
    tracing::info!("Using rudderc at {}", rudderc.path().display());

    // Stateless: a `Rudder` is built for each request, so shared state lives outside it
    let tool_router = Rudder::router(mode);
    let prompt_router = Arc::new(Rudder::prompt_router());
    let handler_api = api.clone();
    let service = StreamableHttpService::new(
        move || {
            Ok(Rudder {
                api: handler_api.clone(),
                rudderc: rudderc.clone(),
                mode,
                tool_router: tool_router.clone(),
                prompt_router: prompt_router.clone(),
            })
        },
        Arc::new(NeverSessionManager::default()),
        StreamableHttpServerConfig::default()
            .with_legacy_session_mode(false)
            .with_stateless_protocol_metadata_required(true)
            .with_json_response(true),
    );

    let router = axum::Router::new()
        .nest_service("/mcp", service)
        .layer(middleware::from_fn_with_state(api, require_token));
    let listener = tokio::net::TcpListener::bind(listen)
        .await
        .with_context(|| format!("could not listen on {listen}"))?;
    tracing::info!("Starting MCP server on http://{listen}/mcp ({mode:?})");
    Ok((listener, router))
}

/// Resolves on SIGTERM (systemd stop) or SIGINT (Ctrl-C)
async fn shutdown_signal() {
    let mut terminate =
        signal(SignalKind::terminate()).expect("SIGTERM handler can be installed at startup");
    tokio::select! {
        _ = terminate.recv() => tracing::info!("SIGTERM received, shutting down"),
        _ = tokio::signal::ctrl_c() => tracing::info!("SIGINT received, shutting down"),
    }
}
