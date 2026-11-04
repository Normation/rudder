// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

//! End-to-end tests: the server against a mock Rudder API, called over HTTP as an MCP client would.
//! Each test runs its own mock and server on random ports.

use std::sync::{Arc, Mutex, OnceLock};

use axum::{
    Router,
    http::{HeaderMap, StatusCode},
    routing::get,
};
use pretty_assertions::assert_eq;
use serde_json::{Value, json};

use crate::{api::RudderApi, config::ApiConfig, serve};

/// Mock Rudder API: the token check and the system endpoints. Tokens: `ro` identifies a read-only
/// account, `user` a user token (no `authorizationType`), `acl` an account that can read its own
/// account but not the system endpoints, `narrow` is valid but cannot read its own account,
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
    let app = Router::new()
        .route("/rudder/api/latest/apiaccounts/token", get(token_account))
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
async fn call_tool(url: &str, token: Option<&str>, name: &str) -> (StatusCode, String) {
    let request = json!({"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {
        "name": name,
        "arguments": {},
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
async fn tool_result(url: &str, token: &str, name: &str) -> (bool, String) {
    let (status, body) = call_tool(url, Some(token), name).await;
    assert_eq!(status, StatusCode::OK, "{body}");
    let response: Value = serde_json::from_str(&body).unwrap();
    let text = response["result"]["content"][0]["text"]
        .as_str()
        .unwrap()
        .to_owned();
    (response["result"]["isError"] == true, text)
}

/// Text answered by a tool that succeeds
async fn tool_text(url: &str, token: &str, name: &str) -> String {
    let (is_error, text) = tool_result(url, token, name).await;
    assert!(!is_error, "{text}");
    text
}

#[tokio::test]
async fn whoami_describes_the_caller() {
    let url = mcp_server(&mock_rudder().await).await;
    let whoami = async |token| -> Value {
        serde_json::from_str(&tool_text(&url, token, "whoami").await).unwrap()
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
    let info: Value = serde_json::from_str(&tool_text(&url, "ro", "server_info").await).unwrap();
    assert_eq!(info["rudder"]["version"], "9.1.0");
    assert_eq!(info["system"], json!({}));
    assert_eq!(info["status"], "OK");
    // No policies: no details
    assert_eq!(info["compliance"], json!({}));
}

#[tokio::test]
async fn api_errors_are_tool_errors() {
    // The model sees why, so it can tell the user the token lacks the right
    let url = mcp_server(&mock_rudder().await).await;
    let (is_error, text) = tool_result(&url, "acl", "server_info").await;
    assert!(is_error, "{text}");
    assert_eq!(
        text,
        r#"Rudder API returned 403 Forbidden: {"result":"error","errorDetails":"Not authorized"}"#
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
        let (status, body) = call_tool(&url, token, "whoami").await;
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
    let (status, body) = call_tool(&url, Some("ro"), "whoami").await;
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
    let (status, _) = call_tool(&url, Some("ro"), "whoami").await;
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
    tool_text(&url, "audit", "whoami").await;
    call_tool(&url, Some("secret-unknown-token"), "whoami").await;
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
        api_call.contains(r#"}:token_check:rudder_api{method=GET path="apiaccounts/token"}"#),
        "{api_call}"
    );
    // Refusals have the request context, but no account
    let refused = line("MCP request refused rejection=Rejected");
    assert!(refused.contains(r#"mcp_name="whoami"}: "#), "{refused}");
    assert!(!logs.contains("secret-unknown-token"), "{logs}");
}
