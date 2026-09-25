//! End-to-end tests: the server as `main` starts it, against a mock Rudder API, called over HTTP as
//! an MCP client would. Each test runs its own mock and server on random ports.

use std::{
    collections::BTreeMap,
    sync::{Arc, Mutex},
};

use axum::{
    Router,
    extract::{Path, Query, State},
    http::{HeaderMap, Method, StatusCode},
    routing::any,
};
use pretty_assertions::assert_eq;
use serde_json::{Value, json};

use crate::{
    config::{ApiConfig, ApiUrl, Config, RuddercConfig, ServerConfig},
    start,
};

/// Requests received by the mock Rudder API: `"<verb> <path>[?<decoded query>]"` and the
/// `X-API-Token` value
type Received = Arc<Mutex<Vec<(String, String)>>>;

const NODE1_ID: &str = "8b168194-c0b4-41ab-b2b5-9571a8906d59";

/// Mock Rudder API. Tokens: `rw` and `ro` identify accounts with these rights, `narrow` is valid
/// but cannot read its own account, anything else is unknown. Nodes: `NODE1_ID` is
/// `node1.example.com`, two nodes share `dup.example.com`, `root` is `server.example.com` and, as
/// in Rudder, only matches queries with `select=nodeAndPolicyServer`. Returned nodes echo the
/// `include` parameter.
async fn mock_rudder() -> (String, Received) {
    async fn handle(
        State(received): State<Received>,
        method: Method,
        Path(path): Path<String>,
        Query(query): Query<BTreeMap<String, String>>,
        headers: HeaderMap,
    ) -> (StatusCode, String) {
        let token = headers
            .get("X-API-Token")
            .and_then(|v| v.to_str().ok())
            .unwrap_or_default()
            .to_owned();
        let mut request = format!("{method} {path}");
        if !query.is_empty() {
            let params: Vec<String> = query.iter().map(|(k, v)| format!("{k}={v}")).collect();
            request.push_str(&format!("?{}", params.join("&")));
        }
        received.lock().unwrap().push((request, token.clone()));
        let include = query.get("include").cloned().unwrap_or_default();
        let node =
            |id: &str, hostname: &str| json!({"id": id, "hostname": hostname, "include": include});
        let nodes =
            |nodes: Vec<Value>| json!({"result": "success", "data": {"nodes": nodes}}).to_string();
        let account = |rights: &str| {
            json!({"result": "success", "action": "getTokenAccount", "id": "x", "data": {"accounts": [
                {"id": format!("{rights}-account-id"), "name": format!("Account {rights}"),
                 "status": "enabled", "authorizationType": rights}
            ]}})
            .to_string()
        };
        match (token.as_str(), method, path.as_str()) {
            ("rw" | "ro" | "narrow", _, _) => {}
            _ => return (StatusCode::UNAUTHORIZED, "Unauthorized".to_owned()),
        }
        match (token.as_str(), path.as_str()) {
            ("narrow", "apiaccounts/token") => (StatusCode::FORBIDDEN, "{}".to_owned()),
            (rights, "apiaccounts/token") => (StatusCode::OK, account(rights)),
            (_, "system/info") => (
                StatusCode::OK,
                json!({"result": "success", "data": {
                    "rudder": {"version": "9.2.0"},
                    "system": {"jvm": {"version": "21", "cmd": "java -Xmx1024m"}}
                }})
                .to_string(),
            ),
            (_, "system/status") => (
                StatusCode::OK,
                r#"{"result":"success","data":{"global":"OK"}}"#.to_owned(),
            ),
            (_, "system/healthcheck") => (
                StatusCode::OK,
                r#"{"result":"success","data":[{"name":"CPU cores","msg":"Only one core","status":"Warning"}]}"#.to_owned(),
            ),
            ("ro", "system/reload/groups") => (StatusCode::FORBIDDEN, "read-only".to_owned()),
            (_, "system/reload/groups") => (StatusCode::OK, r#"{"result":"success"}"#.to_owned()),
            (_, "nodes") => {
                let condition: Value = serde_json::from_str(&query["where"]).unwrap();
                let found = match condition[0]["value"].as_str().unwrap() {
                    "node1.example.com" => vec![node(NODE1_ID, "node1.example.com")],
                    "dup.example.com" => vec![
                        node("dup-1", "dup.example.com"),
                        node("dup-2", "dup.example.com"),
                    ],
                    "server.example.com"
                        if query.get("select").map(String::as_str)
                            == Some("nodeAndPolicyServer") =>
                    {
                        vec![node("root", "server.example.com")]
                    }
                    _ => vec![],
                };
                (StatusCode::OK, nodes(found))
            }
            (_, path) if path == format!("nodes/{NODE1_ID}") => (
                StatusCode::OK,
                nodes(vec![node(NODE1_ID, "node1.example.com")]),
            ),
            (_, path) if path.starts_with("nodes/") => (
                StatusCode::NOT_FOUND,
                r#"{"result":"error","errorDetails":"Node not found"}"#.to_owned(),
            ),
            (_, "compliance") => (
                StatusCode::OK,
                r#"{"result":"success","data":{"globalCompliance":{"compliance":50,"complianceDetails":{"error":50.0,"successAlreadyOK":50.0}}}}"#.to_owned(),
            ),
            (_, "compliance/rules") => (
                StatusCode::OK,
                json!({"result": "success", "data": {"rules": [
                    {"id": "rule1", "name": "Web", "compliance": 0.0, "complianceDetails": {"error": 100.0}},
                    {"id": "rule2", "name": "Base", "compliance": 100.0, "complianceDetails": {"successAlreadyOK": 100.0}}
                ]}})
                .to_string(),
            ),
            (_, path) if path == format!("compliance/nodes/{NODE1_ID}") => (
                StatusCode::OK,
                json!({"result": "success", "data": {"nodes": [{
                    "id": NODE1_ID, "name": "node1.example.com", "compliance": 0.0,
                    "complianceDetails": {"error": 100.0},
                    "rules": [{"id": "rule1", "name": "Web", "compliance": 0.0, "complianceDetails": {"error": 100.0},
                        "directives": [{"id": "dir1", "name": "Nginx", "compliance": 0.0, "complianceDetails": {"error": 100.0},
                            "components": [{"name": "Package", "compliance": 0.0, "complianceDetails": {"error": 100.0},
                                "values": [{"value": "nginx", "reports": [{"status": "error", "message": "nginx not installable"}]}]}]}]}]
                }]}})
                .to_string(),
            ),
            (_, "compliance/rules/rule1") => (
                StatusCode::OK,
                json!({"result": "success", "data": {"rules": [{
                    "id": "rule1", "name": "Web", "compliance": 0.0, "complianceDetails": {"error": 100.0},
                    "directives": [{"id": "dir1", "name": "Nginx", "compliance": 0.0, "complianceDetails": {"error": 100.0},
                        "components": [{"name": "Package", "compliance": 0.0, "complianceDetails": {"error": 100.0},
                            "nodes": [{"id": NODE1_ID, "name": "node1.example.com", "compliance": 0.0, "complianceDetails": {"error": 100.0},
                                "values": [{"value": "nginx", "reports": [{"status": "error", "message": "nginx not installable"}]}]}]}]}]
                }]}})
                .to_string(),
            ),
            (_, "rules/rule1") => (
                StatusCode::OK,
                json!({"result": "success", "data": {"rules": [{
                    "id": "rule1", "displayName": "Web", "shortDescription": "Web servers",
                    "longDescription": "", "directives": ["dir1", "dir-forbidden"],
                    "targets": [{"include": {"or": ["special:all", "group:group1"]},
                                 "exclude": {"or": ["group:group1"]}}],
                    "enabled": true, "system": false, "policyMode": "enforce",
                    "status": {"value": "In application"}
                }]}})
                .to_string(),
            ),
            (_, "directives/dir1") => (
                StatusCode::OK,
                json!({"result": "success", "data": {"directives": [{
                    "id": "dir1", "displayName": "Nginx", "techniqueName": "packageManagement",
                    "techniqueVersion": "1.0", "enabled": true, "system": false, "policyMode": "audit"
                }]}})
                .to_string(),
            ),
            (_, "directives/dir-forbidden") => (StatusCode::FORBIDDEN, "no right".to_owned()),
            (_, "groups/group1") => (
                StatusCode::OK,
                json!({"result": "success", "data": {"groups": [{
                    "id": "group1", "displayName": "Web servers", "nodeIds": ["a", "b"],
                    "dynamic": true, "enabled": true, "system": false
                }]}})
                .to_string(),
            ),
            _ => (StatusCode::NOT_FOUND, String::new()),
        }
    }

    let received = Received::default();
    let app = Router::new()
        .route("/rudder/api/latest/{*path}", any(handle))
        .with_state(received.clone());
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!(
        "http://{}/rudder/api/latest",
        listener.local_addr().unwrap()
    );
    tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    (url, received)
}

/// Starts the MCP server, returns its endpoint URL
async fn mcp_server(api_url: &str, read_only: bool) -> String {
    let config = Config {
        server: ServerConfig {
            listen: "127.0.0.1:0".parse().unwrap(),
            read_only,
        },
        api: ApiConfig {
            url: ApiUrl::for_tests(api_url),
            tls_skip_verify: false,
        },
        rudderc: RuddercConfig::default(),
    };
    let (listener, router) = start(config).await.unwrap();
    let addr = listener.local_addr().unwrap();
    tokio::spawn(async move { axum::serve(listener, router).await.unwrap() });
    format!("http://{addr}/mcp")
}

/// Sends a stateless (2026-07-28) MCP request, returns the HTTP status and body
async fn rpc(
    url: &str,
    token: Option<&str>,
    method: &str,
    mut params: Value,
) -> (StatusCode, String) {
    params["_meta"] = json!({
        "io.modelcontextprotocol/protocolVersion": "2026-07-28",
        "io.modelcontextprotocol/clientCapabilities": {}
    });
    let mut request = reqwest::Client::new()
        .post(url)
        .header("Content-Type", "application/json")
        .header("Accept", "application/json, text/event-stream")
        .header("MCP-Protocol-Version", "2026-07-28")
        .header("Mcp-Method", method);
    if let Some(name) = params["name"].as_str() {
        request = request.header("Mcp-Name", name);
    }
    if let Some(token) = token {
        request = request.header("Authorization", format!("Bearer {token}"));
    }
    let body = json!({"jsonrpc": "2.0", "id": 1, "method": method, "params": params});
    let response = request.body(body.to_string()).send().await.unwrap();
    let status = response.status();
    (status, response.text().await.unwrap())
}

/// JSON-RPC `result` of a successful request
async fn result(url: &str, token: &str, method: &str, params: Value) -> Value {
    let (status, body) = rpc(url, Some(token), method, params).await;
    assert_eq!(status, StatusCode::OK, "{body}");
    let mut response: Value = serde_json::from_str(&body).unwrap();
    response["result"].take()
}

/// Calls a tool, returns its text and whether it is an error
async fn call_tool(url: &str, token: &str, name: &str, arguments: Value) -> (String, bool) {
    let result = result(
        url,
        token,
        "tools/call",
        json!({"name": name, "arguments": arguments}),
    )
    .await;
    (
        result["content"][0]["text"].as_str().unwrap().to_owned(),
        result["isError"].as_bool().unwrap(),
    )
}

async fn tool_names(url: &str) -> Vec<String> {
    let result = result(url, "rw", "tools/list", json!({})).await;
    let mut names: Vec<String> = result["tools"]
        .as_array()
        .unwrap()
        .iter()
        .map(|t| t["name"].as_str().unwrap().to_owned())
        .collect();
    names.sort();
    names
}

#[tokio::test]
async fn refuses_requests_without_an_identified_token() {
    let (api, _) = mock_rudder().await;
    let url = mcp_server(&api, true).await;
    for (token, expected) in [
        (None, StatusCode::UNAUTHORIZED),
        (Some("unknown"), StatusCode::UNAUTHORIZED),
        (Some("narrow"), StatusCode::FORBIDDEN),
        (Some("ro"), StatusCode::OK),
    ] {
        let (status, body) = rpc(&url, token, "tools/list", json!({})).await;
        assert_eq!(status, expected, "token {token:?}: {body}");
    }
}

#[tokio::test]
async fn refuses_requests_when_rudder_is_unreachable() {
    // A port nothing listens on
    let closed = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let api = format!("http://{}/rudder/api/latest", closed.local_addr().unwrap());
    drop(closed);
    let url = mcp_server(&api, true).await;
    let (status, _) = rpc(&url, Some("rw"), "tools/list", json!({})).await;
    assert_eq!(status, StatusCode::BAD_GATEWAY);
}

#[tokio::test]
async fn write_tools_only_without_read_only() {
    let (api, _) = mock_rudder().await;
    let read_only = [
        "compile_technique",
        "compliance",
        "documentation",
        "methods",
        "node_info",
        "render_template",
        "rule_info",
        "server_health",
        "system_info",
        "whoami",
    ];
    assert_eq!(tool_names(&mcp_server(&api, true).await).await, read_only);
    let mut read_write = read_only.to_vec();
    read_write.push("reload_groups");
    read_write.sort();
    assert_eq!(tool_names(&mcp_server(&api, false).await).await, read_write);

    // Not callable either in read-only mode: the tool does not exist
    let (status, body) = rpc(
        &mcp_server(&api, true).await,
        Some("rw"),
        "tools/call",
        json!({"name": "reload_groups", "arguments": {}}),
    )
    .await;
    assert_eq!(status, StatusCode::BAD_REQUEST, "{body}");
    assert!(body.contains("tool not found"), "{body}");
}

#[tokio::test]
async fn whoami_describes_the_caller() {
    let (api, _) = mock_rudder().await;
    let url = mcp_server(&api, true).await;
    let (text, is_error) = call_tool(&url, "ro", "whoami", json!({})).await;
    assert!(!is_error);
    assert_eq!(
        text,
        "Rudder API account: 'Account ro' (id ro-account-id)\n\
         Rights: read-only: GET on all APIs\n\
         Write tools on this MCP server: disabled"
    );
}

#[tokio::test]
async fn api_tools_forward_the_caller_token() {
    let (api, received) = mock_rudder().await;
    let url = mcp_server(&api, true).await;
    let (text, is_error) = call_tool(&url, "ro", "system_info", json!({})).await;
    assert!(!is_error, "{text}");
    let info: Value = serde_json::from_str(&text).unwrap();
    assert_eq!(
        info,
        json!({"rudder": {"version": "9.2.0"}, "system": {"jvm": {"version": "21"}}})
    );
    // Token checked, then forwarded to the API call
    assert_eq!(
        *received.lock().unwrap(),
        [
            ("GET apiaccounts/token".to_owned(), "ro".to_owned()),
            ("GET system/info".to_owned(), "ro".to_owned()),
        ]
    );
}

#[tokio::test]
async fn rudder_refusal_is_a_tool_error() {
    let (api, _) = mock_rudder().await;
    let url = mcp_server(&api, false).await;
    let (text, is_error) = call_tool(&url, "ro", "reload_groups", json!({})).await;
    assert!(is_error);
    assert!(text.contains("403"), "{text}");
    let (_, is_error) = call_tool(&url, "rw", "reload_groups", json!({})).await;
    assert!(!is_error);
}

#[tokio::test]
async fn render_template_only_minijinja() {
    let (api, _) = mock_rudder().await;
    let url = mcp_server(&api, true).await;
    let (text, is_error) = call_tool(
        &url,
        "ro",
        "render_template",
        json!({"template": "{{ name | upper }}", "data": {"name": "web"}}),
    )
    .await;
    assert!(!is_error, "{text}");
    assert_eq!(text, "WEB");

    let (text, is_error) = call_tool(
        &url,
        "ro",
        "render_template",
        json!({"template": "x", "data": {}, "engine": "jinja2"}),
    )
    .await;
    assert!(is_error);
    assert!(text.contains("unknown field `engine`"), "{text}");
}

#[tokio::test]
async fn documentation_serves_topics() {
    let (api, _) = mock_rudder().await;
    let url = mcp_server(&api, true).await;
    let (text, is_error) = call_tool(
        &url,
        "ro",
        "documentation",
        json!({"topic": "technique-syntax"}),
    )
    .await;
    assert!(!is_error);
    assert!(text.starts_with("# Technique syntax"), "{text}");
    let (text, is_error) = call_tool(&url, "ro", "documentation", json!({"topic": "nope"})).await;
    assert!(is_error);
    assert!(text.contains("technique-syntax"), "{text}");
}

#[tokio::test]
async fn write_technique_prompt_embeds_goal_and_syntax() {
    let (api, _) = mock_rudder().await;
    let url = mcp_server(&api, true).await;
    let result = result(
        &url,
        "ro",
        "prompts/get",
        json!({"name": "write_technique", "arguments": {"goal": "Configure chrony"}}),
    )
    .await;
    let text = result["messages"][0]["content"]["text"].as_str().unwrap();
    assert!(text.contains("Configure chrony"));
    assert!(text.contains("# Technique syntax"));
    assert!(!text.contains("{goal}") && !text.contains("{syntax}"));
}

#[tokio::test]
async fn discover_advertises_instructions() {
    let (api, _) = mock_rudder().await;
    let url = mcp_server(&api, true).await;
    let result = result(&url, "ro", "server/discover", json!({})).await;
    assert_eq!(result["supportedVersions"], json!(["2026-07-28"]));
    assert!(
        result["instructions"]
            .as_str()
            .unwrap()
            .contains("compile_technique")
    );
    assert!(result["capabilities"]["tools"].is_object());
    assert!(result["capabilities"]["prompts"].is_object());
}

#[tokio::test]
async fn node_info_by_id_or_hostname() {
    let (api, received) = mock_rudder().await;
    let url = mcp_server(&api, true).await;

    let (text, is_error) = call_tool(&url, "ro", "node_info", json!({"node": NODE1_ID})).await;
    assert!(!is_error, "{text}");
    let node: Value = serde_json::from_str(&text).unwrap();
    assert_eq!(
        node,
        json!({"id": NODE1_ID, "hostname": "node1.example.com", "include": "default"})
    );

    let (text, is_error) = call_tool(
        &url,
        "ro",
        "node_info",
        json!({"node": "node1.example.com", "details": ["software", "networkInterfaces"]}),
    )
    .await;
    assert!(!is_error, "{text}");
    let node: Value = serde_json::from_str(&text).unwrap();
    assert_eq!(node["id"], NODE1_ID);
    assert_eq!(node["include"], "default,software,networkInterfaces");

    // One API call per lookup, with the caller's token
    let calls: Vec<(String, String)> = received
        .lock()
        .unwrap()
        .iter()
        .filter(|(request, _)| request.starts_with("GET nodes"))
        .cloned()
        .collect();
    assert_eq!(
        calls,
        [
            (format!("GET nodes/{NODE1_ID}?include=default"), "ro".to_owned()),
            (
                r#"GET nodes?include=default,software,networkInterfaces&select=nodeAndPolicyServer&where=[{"attribute":"nodeHostname","comparator":"eq","objectType":"node","value":"node1.example.com"}]"#.to_owned(),
                "ro".to_owned()
            ),
        ]
    );
}

#[tokio::test]
async fn node_info_finds_policy_servers_by_hostname() {
    let (api, _) = mock_rudder().await;
    let url = mcp_server(&api, true).await;
    let (text, is_error) = call_tool(
        &url,
        "ro",
        "node_info",
        json!({"node": "server.example.com"}),
    )
    .await;
    assert!(!is_error, "{text}");
    let node: Value = serde_json::from_str(&text).unwrap();
    assert_eq!(node["id"], "root");
}

#[tokio::test]
async fn node_info_errors() {
    let (api, received) = mock_rudder().await;
    let url = mcp_server(&api, true).await;
    for (node, expected) in [
        (
            "unknown.example.com",
            "no node found for 'unknown.example.com'",
        ),
        ("dup.example.com", "use one of their ids: dup-1, dup-2"),
        ("00000000-0000-0000-0000-000000000000", "Node not found"),
    ] {
        let (text, is_error) = call_tool(&url, "ro", "node_info", json!({"node": node})).await;
        assert!(is_error, "{node}: {text}");
        assert!(text.contains(expected), "{node}: {text}");
    }

    // Unknown section names are rejected before any API call
    let (text, is_error) = call_tool(
        &url,
        "ro",
        "node_info",
        json!({"node": NODE1_ID, "details": ["passwords"]}),
    )
    .await;
    assert!(is_error);
    assert!(text.contains("unknown variant `passwords`"), "{text}");

    // Anything but an id is sent as a hostname value, never as a path
    let (_, is_error) = call_tool(&url, "ro", "node_info", json!({"node": "../system/info"})).await;
    assert!(is_error);
    assert!(
        !received
            .lock()
            .unwrap()
            .iter()
            .any(|(request, _)| request.starts_with("GET system/info"))
    );
}

#[tokio::test]
async fn server_health_combines_status_and_checks() {
    let (api, received) = mock_rudder().await;
    let url = mcp_server(&api, true).await;
    let (text, is_error) = call_tool(&url, "ro", "server_health", json!({})).await;
    assert!(!is_error, "{text}");
    assert_eq!(
        text,
        "Status: OK\nChecks:\n- [Warning] CPU cores: Only one core"
    );
    let calls = received.lock().unwrap();
    for path in ["GET system/status", "GET system/healthcheck"] {
        assert!(
            calls
                .iter()
                .any(|(request, token)| request == path && token == "ro"),
            "{calls:?}"
        );
    }
}

#[tokio::test]
async fn compliance_global_node_and_rule() {
    let (api, received) = mock_rudder().await;
    let url = mcp_server(&api, true).await;

    let (text, is_error) = call_tool(&url, "ro", "compliance", json!({})).await;
    assert!(!is_error, "{text}");
    assert_eq!(
        text,
        "Global compliance: 50% [error 50.0%, successAlreadyOK 50.0%]\n\
         Rules (2):\n\
         - Web (rule1): 0.0% [error 100.0%]\n\
         - Base (rule2): 100.0% [successAlreadyOK 100.0%]"
    );

    // By hostname: looked up, then its compliance by id
    let (text, is_error) = call_tool(
        &url,
        "ro",
        "compliance",
        json!({"node": "node1.example.com"}),
    )
    .await;
    assert!(!is_error, "{text}");
    assert!(
        text.contains("- nginx: error: nginx not installable"),
        "{text}"
    );
    assert!(
        received
            .lock()
            .unwrap()
            .iter()
            .any(|(request, _)| request == &format!("GET compliance/nodes/{NODE1_ID}"))
    );

    let (text, is_error) = call_tool(&url, "ro", "compliance", json!({"rule": "rule1"})).await;
    assert!(!is_error, "{text}");
    assert!(text.starts_with("- Web (rule1): 0.0%"), "{text}");
    assert!(text.contains("node1.example.com"), "{text}");
    assert!(
        text.contains("- nginx: error: nginx not installable"),
        "{text}"
    );
}

#[tokio::test]
async fn compliance_errors() {
    let (api, received) = mock_rudder().await;
    let url = mcp_server(&api, true).await;
    for (arguments, expected) in [
        (json!({"node": "root", "rule": "rule1"}), "not both"),
        (json!({"rule": "../nodes"}), "'../nodes' is not a rule id"),
        (json!({"node": "unknown.example.com"}), "no node found"),
    ] {
        let (text, is_error) = call_tool(&url, "ro", "compliance", arguments.clone()).await;
        assert!(is_error, "{arguments}: {text}");
        assert!(text.contains(expected), "{arguments}: {text}");
    }
    // The invalid rule id never reached the API
    assert!(
        !received
            .lock()
            .unwrap()
            .iter()
            .any(|(request, _)| request.contains("compliance/rules/"))
    );
}

#[tokio::test]
async fn rule_info_resolves_directives_and_targets() {
    let (api, received) = mock_rudder().await;
    let url = mcp_server(&api, true).await;
    let (text, is_error) = call_tool(&url, "ro", "rule_info", json!({"rule": "rule1"})).await;
    assert!(!is_error, "{text}");
    assert_eq!(
        text,
        "Rule: Web (rule1)\n\
         Status: In application\n\
         Enabled: yes, system: no, policy mode: enforce\n\
         Description: Web servers\n\
         Directives (2):\n\
         - Nginx (dir1): technique packageManagement 1.0, enabled, policy mode audit\n\
         - dir-forbidden: details unavailable: Rudder API returned 403 Forbidden: no right\n\
         Targets included (2):\n\
         - all nodes, policy servers included (special:all)\n\
         - group Web servers (group1): 2 nodes, dynamic\n\
         Targets excluded (1):\n\
         - group Web servers (group1): 2 nodes, dynamic"
    );
    // Every call carries the caller's token
    assert!(
        received
            .lock()
            .unwrap()
            .iter()
            .all(|(_, token)| token == "ro")
    );

    let (text, is_error) = call_tool(&url, "ro", "rule_info", json!({"rule": "../nodes"})).await;
    assert!(is_error);
    assert!(text.contains("is not a rule id"), "{text}");
}
