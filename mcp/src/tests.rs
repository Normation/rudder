// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

//! End-to-end tests: the server against a mock Rudder API, called over HTTP as an MCP client would.
//! Each test runs its own mock and server on random ports.

use std::sync::{Arc, Mutex, OnceLock};

use std::collections::HashMap;

use axum::{
    Router,
    extract::{Path, Query},
    http::{HeaderMap, StatusCode},
    routing::get,
};
use pretty_assertions::assert_eq;
use serde_json::{Value, json};

use crate::{api::RudderApi, config::ApiConfig, serve};

/// Mock Rudder API: the token check, the system endpoints, the global policy mode (`audit`,
/// overridable) and one node, `root` with hostname
/// `server.example.com`, which echoes the requested `include` (`""` when absent). Tokens: `ro` identifies a read-only
/// account, `user` a user token (no `authorizationType`), `acl` an account that can read its own
/// account but not the system endpoints nor the settings, `narrow` is valid but cannot read its own account,
/// `anonymous` gets an answer without account, `future` an account with an unknown
/// `authorizationType`, `audit` is an `rw` account only used to check the logs, anything else is
/// unknown.
async fn mock_rudder() -> String {
    async fn token_account(headers: HeaderMap) -> (StatusCode, String) {
        let token = headers
            .get("X-API-Token")
            .and_then(|v| v.to_str().ok())
            .unwrap_or_default();
        let accounts = |accounts: Value| {
            json!({"result": "success", "action": "getTokenAccount", "id": "x",
                   "data": {"accounts": accounts}})
            .to_string()
        };
        let account = |authorization_type: Option<&str>| {
            let mut account = json!({"id": format!("{token}-id"), "name": format!("Account {token}"),
                                     "status": "enabled"});
            if let Some(authorization_type) = authorization_type {
                account["authorizationType"] = json!(authorization_type);
            }
            accounts(json!([account]))
        };
        match token {
            "ro" => (StatusCode::OK, account(Some("ro"))),
            "audit" => (StatusCode::OK, account(Some("rw"))),
            "user" => (StatusCode::OK, account(None)),
            "future" => (StatusCode::OK, account(Some("admin"))),
            "acl" => (StatusCode::OK, account(Some("acl"))),
            "anonymous" => (StatusCode::OK, accounts(json!([]))),
            "narrow" => (StatusCode::FORBIDDEN, "{}".to_owned()),
            _ => (StatusCode::UNAUTHORIZED, "Unauthorized".to_owned()),
        }
    }
    /// Answers `body` to the tokens allowed to read the system endpoints
    fn system(body: Value) -> axum::routing::MethodRouter {
        get(move |headers: HeaderMap| async move {
            if headers.get("X-API-Token").unwrap() == "acl" {
                (
                    StatusCode::FORBIDDEN,
                    r#"{"result":"error","errorDetails":"Not authorized"}"#.to_owned(),
                )
            } else {
                (StatusCode::OK, body.to_string())
            }
        })
    }
    /// `GET /nodes` and `GET /nodes/{id}` body with the given nodes
    fn nodes(nodes: Vec<Value>) -> String {
        json!({"action": "nodeDetails", "result": "success", "data": {"nodes": nodes}}).to_string()
    }
    fn root(query: &HashMap<String, String>) -> Value {
        json!({"id": "root", "hostname": "server.example.com", "policyMode": "enforce",
               "include": query.get("include").cloned().unwrap_or_default(),
               "software": [{"name": "openssl", "version": "3.5.1"}, {"name": "bash", "version": "5.2"}]})
    }
    async fn node(
        Path(id): Path<String>,
        Query(query): Query<HashMap<String, String>>,
    ) -> (StatusCode, String) {
        if id == "root" {
            (StatusCode::OK, nodes(vec![root(&query)]))
        } else {
            let error = json!({"action": "nodeDetails", "result": "error",
                               "errorDetails": format!("Node with ID '{id}' was not found in Rudder")});
            (StatusCode::NOT_FOUND, error.to_string())
        }
    }
    /// Finds `root` by its exact hostname, or by the similar hostnames regex of `server`
    async fn search(Query(query): Query<HashMap<String, String>>) -> String {
        let condition = |comparator, value| {
            json!([{"objectType": "node", "attribute": "nodeHostname",
                    "comparator": comparator, "value": value}])
        };
        let condition_sent = serde_json::from_str::<Value>(&query["where"]).unwrap();
        let found = query.get("select").map(String::as_str) == Some("nodeAndPolicyServer")
            && (condition_sent == condition("eq", "server.example.com")
                || condition_sent == condition("regex", r"(?i)server(\..*)?"));
        nodes(if found { vec![root(&query)] } else { vec![] })
    }
    let app = Router::new()
        .route("/rudder/api/latest/apiaccounts/token", get(token_account))
        .route("/rudder/api/latest/nodes", get(search))
        .route("/rudder/api/latest/nodes/{id}", get(node))
        .route(
            "/rudder/api/latest/system/info",
            system(
                json!({"action": "getSystemInfo", "result": "success", "data": {
                    "rudder": {"version": "9.1.0", "instanceId": "i-1", "relays": []},
                    "system": {"jvm": {"version": "21", "cmd": "/usr/bin/java -Xmx1024m"}},
                    "nodes": {"total": 3},
                    "plugins": []
                }}),
            ),
        )
        .route(
            "/rudder/api/latest/system/status",
            system(json!({"action": "getStatus", "result": "success",
                              "data": {"global": "OK"}})),
        )
        .route(
            "/rudder/api/latest/settings/global_policy_mode",
            system(
                json!({"action": "getSetting", "id": "global_policy_mode", "result": "success",
                          "data": {"settings": {"global_policy_mode": "audit"}}}),
            ),
        )
        .route(
            "/rudder/api/latest/settings/global_policy_mode_overridable",
            system(
                json!({"action": "getSetting", "id": "global_policy_mode_overridable",
                          "result": "success",
                          "data": {"settings": {"global_policy_mode_overridable": true}}}),
            ),
        )
        .route(
            "/rudder/api/latest/compliance",
            system(json!({"action": "getGlobalCompliance", "result": "success",
                          "data": {"globalCompliance": {"compliance": -1}}})),
        );
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    url
}

/// Starts the MCP server using the Rudder server at `rudder_url`, returns its endpoint URL
async fn mcp_server(rudder_url: &str) -> String {
    let config = ApiConfig {
        url: rudder_url.to_owned().try_into().unwrap(),
    };
    let api = RudderApi::new(&config).unwrap();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}/mcp", listener.local_addr().unwrap());
    tokio::spawn(async move { serve(listener, api).await.unwrap() });
    url
}

/// Calls a tool with a stateless (2026-07-28) MCP request, returns the HTTP status and body
async fn call_tool(
    url: &str,
    token: Option<&str>,
    name: &str,
    arguments: Value,
) -> (StatusCode, String) {
    let request = json!({"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {
        "name": name,
        "arguments": arguments,
        "_meta": {
            "io.modelcontextprotocol/protocolVersion": "2026-07-28",
            "io.modelcontextprotocol/clientCapabilities": {}
        }
    }});
    let mut request = reqwest::Client::new()
        .post(url)
        .header("Content-Type", "application/json")
        .header("Accept", "application/json, text/event-stream")
        .header("MCP-Protocol-Version", "2026-07-28")
        .header("Mcp-Method", "tools/call")
        .header("Mcp-Name", name)
        .body(request.to_string());
    if let Some(token) = token {
        request = request.header("Authorization", format!("Bearer {token}"));
    }
    let response = request.send().await.unwrap();
    let status = response.status();
    (status, response.text().await.unwrap())
}

/// Text answered by a tool, and whether it is an error
async fn tool_result(url: &str, token: &str, name: &str, arguments: Value) -> (bool, String) {
    let (status, body) = call_tool(url, Some(token), name, arguments).await;
    assert_eq!(status, StatusCode::OK, "{body}");
    let response: Value = serde_json::from_str(&body).unwrap();
    let text = response["result"]["content"][0]["text"]
        .as_str()
        .unwrap()
        .to_owned();
    (response["result"]["isError"] == true, text)
}

/// Text answered by a tool that succeeds
async fn tool_text(url: &str, token: &str, name: &str, arguments: Value) -> String {
    let (is_error, text) = tool_result(url, token, name, arguments).await;
    assert!(!is_error, "{text}");
    text
}

#[tokio::test]
async fn whoami_describes_the_caller() {
    let url = mcp_server(&mock_rudder().await).await;
    let whoami = async |token| -> Value {
        serde_json::from_str(&tool_text(&url, token, "whoami", json!({})).await).unwrap()
    };
    assert_eq!(
        whoami("ro").await,
        json!({"id": "ro-id", "name": "Account ro", "rights": "ro",
               "rightsDescription": "read-only: GET on all APIs"})
    );
    assert_eq!(
        whoami("user").await,
        json!({"id": "user-id", "name": "Account user", "rights": "owner",
               "rightsDescription": "those of the user owning the token (api-authorization plugin)"})
    );
}

#[tokio::test]
async fn server_info_with_status() {
    let url = mcp_server(&mock_rudder().await).await;
    let info: Value =
        serde_json::from_str(&tool_text(&url, "ro", "server_info", json!({})).await).unwrap();
    assert_eq!(info["rudder"]["version"], "9.1.0");
    assert_eq!(info["system"], json!({}));
    assert_eq!(info["status"], "OK");
    // No policies: no details
    assert_eq!(info["compliance"], json!({}));
}

#[tokio::test]
async fn node_info_by_id_or_hostname() {
    let url = mcp_server(&mock_rudder().await).await;
    let node_info = async |arguments| -> Value {
        serde_json::from_str(&tool_text(&url, "ro", "node_info", arguments).await).unwrap()
    };
    let node = node_info(json!({"node": "root"})).await;
    assert_eq!(node["include"], "default");
    assert_eq!(node["policyMode"], "enforce");
    assert_eq!(node["policyModeSource"], "node");
    let node = node_info(
        json!({"node": "server.example.com", "details": ["fileSystems"],
                                "software": "SSL"}),
    )
    .await;
    assert_eq!(node["id"], "root");
    assert_eq!(node["include"], "default,fileSystems,software");
    assert_eq!(
        node["software"],
        json!([{"name": "openssl", "version": "3.5.1"}])
    );

    let (is_error, text) = tool_result(&url, "ro", "node_info", json!({"node": "web"})).await;
    assert!(
        is_error && text.starts_with("no node found for 'web': give a node id"),
        "{text}"
    );
    // A short name: the fully qualified one to retry with
    let (is_error, text) = tool_result(&url, "ro", "node_info", json!({"node": "server"})).await;
    assert!(
        is_error && text.contains("Similar hostnames (id): server.example.com (root)."),
        "{text}"
    );
    let unknown = "8b168194-c0b4-41ab-b2b5-9571a8906d59";
    let (is_error, text) = tool_result(&url, "ro", "node_info", json!({"node": unknown})).await;
    assert!(
        is_error && text.contains("was not found in Rudder"),
        "{text}"
    );
}

#[tokio::test]
async fn node_info_without_the_global_policy_mode() {
    // `acl` can read the node but not the settings: the node anyway, with why the mode is raw
    let url = mcp_server(&mock_rudder().await).await;
    let node: Value =
        serde_json::from_str(&tool_text(&url, "acl", "node_info", json!({"node": "root"})).await)
            .unwrap();
    assert_eq!(node["policyMode"], "enforce");
    let reason = node["policyModeUnresolved"].as_str().unwrap();
    assert!(
        reason.contains("GET settings/global_policy_mode")
            && reason.contains("administration_read"),
        "{reason}"
    );
}

#[tokio::test]
async fn api_errors_are_tool_errors() {
    // The model sees every failed call and the right it needs, so it can tell the user
    let url = mcp_server(&mock_rudder().await).await;
    let (is_error, text) = tool_result(&url, "acl", "server_info", json!({})).await;
    assert!(is_error, "{text}");
    let refused = |path, right| {
        format!(
            "Rudder API refused GET {path} (403 Forbidden): requires the {right} right, or this \
             path in the token's ACL: {{\"result\":\"error\",\"errorDetails\":\"Not authorized\"}}"
        )
    };
    assert_eq!(
        text,
        [
            refused("system/info", "administration_read"),
            refused("system/status", "administration_read"),
            refused("compliance", "compliance_read"),
        ]
        .join("\n")
    );
}

#[tokio::test]
async fn refuses_requests_without_an_identified_token() {
    let url = mcp_server(&mock_rudder().await).await;
    for (token, expected) in [
        (None, StatusCode::UNAUTHORIZED),
        (Some("unknown"), StatusCode::UNAUTHORIZED),
        (Some("narrow"), StatusCode::FORBIDDEN),
        (Some("anonymous"), StatusCode::BAD_GATEWAY),
        (Some("future"), StatusCode::BAD_GATEWAY),
    ] {
        let (status, body) = call_tool(&url, token, "whoami", json!({})).await;
        assert_eq!(status, expected, "token {token:?}: {body}");
    }
}

#[tokio::test]
async fn does_not_follow_redirects_with_the_token() {
    // Accepts any token, and records the ones it receives
    let received = Arc::new(Mutex::new(Vec::<String>::new()));
    let elsewhere = {
        let received = received.clone();
        Router::new().fallback(move |headers: HeaderMap| async move {
            if let Some(token) = headers.get("X-API-Token") {
                received
                    .lock()
                    .unwrap()
                    .push(token.to_str().unwrap().to_owned());
            }
            json!({"data": {"accounts": [{"id": "x", "name": "X", "authorizationType": "rw"}]}})
                .to_string()
        })
    };
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let elsewhere_url = format!("http://{}/", listener.local_addr().unwrap());
    tokio::spawn(async move { axum::serve(listener, elsewhere).await.unwrap() });
    // A Rudder that redirects the token check there
    let rudder = Router::new().route(
        "/rudder/api/latest/apiaccounts/token",
        get(move || async move { axum::response::Redirect::temporary(&elsewhere_url) }),
    );
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let rudder_url = format!("http://{}", listener.local_addr().unwrap());
    tokio::spawn(async move { axum::serve(listener, rudder).await.unwrap() });

    let url = mcp_server(&rudder_url).await;
    let (status, body) = call_tool(&url, Some("ro"), "whoami", json!({})).await;
    assert_eq!(status, StatusCode::BAD_GATEWAY, "{body}");
    assert!(received.lock().unwrap().is_empty());
}

#[tokio::test]
async fn refuses_requests_when_rudder_is_unreachable() {
    // A port nothing listens on
    let closed = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let rudder_url = format!("http://{}", closed.local_addr().unwrap());
    drop(closed);
    let url = mcp_server(&rudder_url).await;
    let (status, _) = call_tool(&url, Some("ro"), "whoami", json!({})).await;
    assert_eq!(status, StatusCode::BAD_GATEWAY);
}

/// Log lines of all tests, from `debug`. The subscriber is global: tracing caches per process
/// whether a callsite is enabled, so a thread-scoped one misses events whose callsite was first hit
/// by a test on another thread.
fn logs() -> String {
    #[derive(Clone, Default)]
    struct Logs(Arc<Mutex<Vec<u8>>>);
    impl std::io::Write for Logs {
        fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
            self.0.lock().unwrap().write(buf)
        }
        fn flush(&mut self) -> std::io::Result<()> {
            Ok(())
        }
    }
    static LOGS: OnceLock<Logs> = OnceLock::new();
    let logs = LOGS.get_or_init(|| {
        let logs = Logs::default();
        let writer = logs.clone();
        let subscriber = tracing_subscriber::fmt()
            .with_max_level(tracing::Level::DEBUG)
            .without_time()
            .with_ansi(false)
            .with_writer(move || writer.clone())
            .finish();
        tracing::subscriber::set_global_default(subscriber).unwrap();
        logs
    });
    String::from_utf8(logs.0.lock().unwrap().clone()).unwrap()
}

#[tokio::test]
async fn requests_are_logged_with_the_caller() {
    logs();
    let url = mcp_server(&mock_rudder().await).await;
    tool_text(&url, "audit", "whoami", json!({})).await;
    call_tool(&url, Some("secret-unknown-token"), "whoami", json!({})).await;
    let logs = logs();
    let line = |text: &str| {
        logs.lines()
            .find(|line| line.contains(text))
            .unwrap_or_else(|| panic!("no log line with '{text}' in:\n{logs}"))
    };

    let accepted = line(r#"MCP request account_name="Account audit""#);
    assert!(
        accepted.starts_with(" INFO mcp_request{client=127.0.0.1:"),
        "{accepted}"
    );
    assert!(
        accepted.contains(
            r#"mcp_method="tools/call" mcp_name="whoami" account="audit-id" rights="rw"}"#
        ),
        "{accepted}"
    );
    assert!(accepted.contains("http_status=200"), "{accepted}");
    let api_call = line("Rudder API answered status=200");
    assert!(
        api_call
            .contains(r#"}:token_check:rudder_api{method=GET path="apiaccounts/token" query=[]}"#),
        "{api_call}"
    );
    // Refusals have the request context, but no account
    let refused = line("MCP request refused rejection=Rejected");
    assert!(refused.contains(r#"mcp_name="whoami"}: "#), "{refused}");
    assert!(!logs.contains("secret-unknown-token"), "{logs}");
}
