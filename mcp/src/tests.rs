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
/// overridable) and one node (with its compliance), `root` with hostname
/// `server.example.com`, which echoes the requested `include` (`""` when absent). Tokens: `ro` identifies a read-only
/// account, `user` a user token (no `authorizationType`), `acl` an account that can read its own
/// account but not the system endpoints, the settings nor the compliance, `narrow` is valid but cannot read its own account,
/// `system` the system token, `user-system` a user token ending like it, `anonymous` gets an answer without account, `future` an account with an unknown
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
            "audit" | "legacy" => (StatusCode::OK, account(Some("rw"))),
            "user" => (StatusCode::OK, account(None)),
            // As Rudder sends it: no `authorizationType`, a fixed id. A user token named like
            // the system one is still a user token.
            "system" => (
                StatusCode::OK,
                accounts(json!([{"id": "rudder-system-api-account",
                "name": "Rudder system account", "status": "enabled"}])),
            ),
            "user-system" => (StatusCode::OK, account(None)),
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
    /// With its OS when `include` names it, as `list_nodes` asks
    fn root(query: &HashMap<String, String>) -> Value {
        let include = query.get("include").cloned().unwrap_or_default();
        let mut root = json!({"id": "root", "hostname": "server.example.com", "policyMode": "enforce",
               "include": include, "status": "accepted",
               "software": [{"name": "openssl", "version": "3.5.1"}, {"name": "bash", "version": "5.2"}]});
        if include.split(',').any(|field| field == "os") {
            root["os"] = json!({"type": "Linux", "name": "Debian", "version": "13",
                                "fullName": "Debian GNU/Linux 13 (trixie)", "kernelVersion": "6.12.48"});
        }
        root
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
    /// Every node (`root` and `web1`) without criteria. Finds `root` by its exact hostname, by the
    /// similar hostnames regex of `server`, or as the Debian node of an `or` composition.
    async fn search(Query(query): Query<HashMap<String, String>>) -> String {
        let Some(condition_sent) = query.get("where") else {
            let web1 = json!({"id": "8b168194-c0b4-41ab-b2b5-9571a8906d59",
                              "hostname": "web1.example.com", "status": "accepted",
                              "os": {"type": "Windows", "name": "Windows2019", "version": "10.0",
                                     "fullName": "Windows Server 2019",
                                     "kernelVersion": "10.0.17763"}});
            return nodes(vec![web1, root(&query)]);
        };
        let condition = |attribute, comparator, value| {
            json!([{"objectType": "node", "attribute": attribute,
                    "comparator": comparator, "value": value}])
        };
        let condition_sent = serde_json::from_str::<Value>(condition_sent).unwrap();
        let found = query.get("select").map(String::as_str) == Some("nodeAndPolicyServer")
            && (condition_sent == condition("nodeHostname", "eq", "server.example.com")
                || condition_sent == condition("nodeHostname", "regex", r"(?i)server(\..*)?")
                || (condition_sent == condition("osName", "eq", "Debian")
                    && query["composition"] == "or"));
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
            "/rudder/api/latest/compliance/nodes/root",
            system(
                json!({"action": "getNodeComplianceId", "result": "success", "data": {"nodes": [{
                    "id": "root", "name": "server.example.com", "compliance": 50.0,
                    "complianceDetails": {"error": 50.0, "successAlreadyOK": 50.0},
                    "rules": [{"id": "r1", "name": "Web", "compliance": 50.0,
                               "complianceDetails": {"error": 50.0, "successAlreadyOK": 50.0},
                               "directives": []}]
                }]}}),
            ),
        )
        .route(
            "/rudder/api/latest/compliance/nodes/12345678-0000-0000-0000-000000000001",
            get(|Query(query): Query<HashMap<String,String>>| async move {
                assert_eq!(query.get("level").map(String::as_str), Some("4"));
                json!({"data":{"nodes":[{"id": POLICY_NODE, "name":"web", "rules":[
                    {"id":"r1","name":"Web","directives":[{"id":"d1","name":"Site"},{"id":"d2","name":"Packages"}]}
                ]}]}}).to_string()
            }),
        )
        .route(
            "/rudder/api/latest/groups",
            system(json!({"action": "listGroups", "result": "success", "data": {"groups": [
                {"id": "g2", "displayName": "Web servers", "nodeIds": [POLICY_NODE, "root"]},
                {"id": "g1", "displayName": "Databases", "nodeIds": []},
                {"id": "g3", "displayName": "All nodes", "nodeIds": [POLICY_NODE, "root"]}
            ]}})),
        )
        .route(
            "/rudder/api/latest/nodes/{id}/inheritedProperties",
            // As Rudder sends them, with the values along the hierarchy left out here
            get(|Path(id): Path<String>| async move {
                json!({"action": "nodeInheritedProperties", "result": "success", "data": [{
                    "nodeId": id,
                    "properties": [
                        {"name": "env", "value": "prod", "provider": "inherited",
                         "hierarchy": {"kind": "group", "id": "g2", "name": "Web servers"},
                         "hierarchyStatus": {"hasChildTypeConflicts": false, "fullHierarchy":
                            {"kind": "group", "id": "g2", "name": "Web servers", "valueType": "String",
                             "parent": {"kind": "global", "valueType": "String"}}}},
                        {"name": "site", "value": {"root": "/srv/www"}, "description": "Site settings",
                         "hierarchyStatus": {"hasChildTypeConflicts": false, "fullHierarchy":
                            {"kind": "node", "id": id, "name": "web", "valueType": "Object"}},
                         "origval": {"root": "/srv/www"}}
                    ]
                }]})
                .to_string()
            }),
        )
        .route(
            "/rudder/api/latest/directives/{id}",
            get(|Path(id): Path<String>| async move {
                // As Rudder sends them: the variables of an editor technique's parameters are named
                // by the uppercased parameter id, and each method has a section with a report key
                let (technique, sections) = if id == "d1" {
                    ("site", json!([
                        {"section": {"name": "Technique parameters", "vars": [
                            {"var": {"name": "2628FA31-0C5B-4B4E-9D1F-7A1F5E7F0001", "value": "/srv/www"}},
                            {"var": {"name": "UNKNOWN_PARAMETER", "value": "kept"}}
                        ]}},
                        {"section": {"name": "Site file", "vars": [
                            {"var": {"name": "expectedReportKey Site file", "value": "/srv/www"}}
                        ]}}
                    ]))
                } else {
                    ("packages", json!([{"section": {"name": "Packages", "vars": [
                        {"var": {"name": "PACKAGE_LIST", "value": "vim"}}
                    ]}}]))
                };
                json!({"data":{"directives":[{"id":id,"displayName":technique,"techniqueName":technique,
                    "techniqueVersion":"2.0","parameters":{"section":{"name":"sections","sections":sections}}}]}}).to_string()
            }),
        )
        .route(
            "/rudder/api/latest/techniques/{name}/2.0",
            get(|Path(name): Path<String>, Query(query): Query<HashMap<String,String>>, headers: HeaderMap| async move {
                assert_eq!(query.get("format").map(String::as_str), Some("yaml"));
                if headers["X-API-Token"] == "acl" {
                    return (StatusCode::FORBIDDEN, String::new());
                }
                let technique = if name == "site" {
                    json!({"content": SITE_YAML})
                } else {
                    json!({"name": "Package management", "id": name, "version": "2.0", "source": "built-in"})
                };
                (StatusCode::OK, json!({"data":{"techniques":[technique]}}).to_string())
            }),
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

/// Sends a stateless (2026-07-28) MCP request, returns the HTTP status and body. `name` is the
/// tool's, for a tool call.
async fn mcp_request(
    url: &str,
    token: Option<&str>,
    method: &str,
    name: Option<&str>,
    mut params: Value,
) -> (StatusCode, String) {
    params["_meta"] = json!({
        "io.modelcontextprotocol/protocolVersion": "2026-07-28",
        "io.modelcontextprotocol/clientCapabilities": {}
    });
    let request = json!({"jsonrpc": "2.0", "id": 1, "method": method, "params": params});
    let mut request = reqwest::Client::new()
        .post(url)
        .header("Content-Type", "application/json")
        .header("Accept", "application/json, text/event-stream")
        .header("MCP-Protocol-Version", "2026-07-28")
        .header("Mcp-Method", method)
        .body(request.to_string());
    if let Some(name) = name {
        request = request.header("Mcp-Name", name);
    }
    if let Some(token) = token {
        request = request.header("Authorization", format!("Bearer {token}"));
    }
    let response = request.send().await.unwrap();
    let status = response.status();
    (status, response.text().await.unwrap())
}

/// Calls a tool, returns the HTTP status and body
async fn call_tool(
    url: &str,
    token: Option<&str>,
    name: &str,
    arguments: Value,
) -> (StatusCode, String) {
    let params = json!({"name": name, "arguments": arguments});
    mcp_request(url, token, "tools/call", Some(name), params).await
}

/// Text answered by a tool, and whether it is an error
async fn tool_result(
    url: &str,
    token: Option<&str>,
    name: &str,
    arguments: Value,
) -> (bool, String) {
    let (status, body) = call_tool(url, token, name, arguments).await;
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
    let (is_error, text) = tool_result(url, Some(token), name, arguments).await;
    assert!(!is_error, "{text}");
    text
}

#[tokio::test]
async fn get_current_account_describes_the_caller() {
    let url = mcp_server(&mock_rudder().await).await;
    let get_current_account = async |token| -> Value {
        serde_json::from_str(&tool_text(&url, token, "get_current_account", json!({})).await)
            .unwrap()
    };
    assert_eq!(
        get_current_account("ro").await,
        json!({"id": "ro-id", "name": "Account ro", "rights": "ro",
               "rightsDescription": "read-only: GET on all APIs"})
    );
    assert_eq!(
        get_current_account("user").await,
        json!({"id": "user-id", "name": "Account user", "rights": "owner",
               "rightsDescription": "those of the user owning the token (api-authorization plugin)"})
    );
    assert_eq!(
        get_current_account("system").await,
        json!({"id": "rudder-system-api-account", "name": "Rudder system account",
               "rights": "system",
               "rightsDescription": "Rudder's internal system token: all verbs on all APIs"})
    );
    assert_eq!(get_current_account("user-system").await["rights"], "owner");
}

#[tokio::test]
async fn get_server_info_with_status() {
    let url = mcp_server(&mock_rudder().await).await;
    let info: Value =
        serde_json::from_str(&tool_text(&url, "ro", "get_server_info", json!({})).await).unwrap();
    assert_eq!(info["rudder"]["version"], "9.1.0");
    assert_eq!(info["system"], json!({}));
    assert_eq!(info["status"], "OK");
    // No policies: no details
    assert_eq!(info["compliance"], json!({}));
}

#[tokio::test]
async fn get_node_by_id_or_hostname() {
    let url = mcp_server(&mock_rudder().await).await;
    let get_node = async |arguments| -> Value {
        serde_json::from_str(&tool_text(&url, "ro", "get_node", arguments).await).unwrap()
    };
    let node = get_node(json!({"node": "root"})).await;
    assert_eq!(node["include"], "default");
    assert_eq!(node["policyMode"], "enforce");
    assert_eq!(node["policyModeSource"], "node");
    let node = get_node(
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

    let (is_error, text) = tool_result(&url, Some("ro"), "get_node", json!({"node": "web"})).await;
    assert!(
        is_error && text.starts_with("no node found for 'web': give a node id"),
        "{text}"
    );
    // A short name: the fully qualified one to retry with
    let (is_error, text) =
        tool_result(&url, Some("ro"), "get_node", json!({"node": "server"})).await;
    assert!(
        is_error && text.contains("Similar hostnames (id): server.example.com (root)."),
        "{text}"
    );
    let unknown = "8b168194-c0b4-41ab-b2b5-9571a8906d59";
    let (is_error, text) =
        tool_result(&url, Some("ro"), "get_node", json!({"node": unknown})).await;
    assert!(
        is_error && text.contains("was not found in Rudder"),
        "{text}"
    );
}

#[tokio::test]
async fn list_nodes_all_or_matching() {
    let url = mcp_server(&mock_rudder().await).await;
    let list_nodes = async |arguments| -> Value {
        serde_json::from_str(&tool_text(&url, "ro", "list_nodes", arguments).await).unwrap()
    };
    let all = list_nodes(json!({})).await;
    assert_eq!(all["total"], 2);
    let hostnames: Vec<&str> = all["nodes"]
        .as_array()
        .unwrap()
        .iter()
        .map(|n| n["hostname"].as_str().unwrap())
        .collect();
    assert_eq!(hostnames, ["server.example.com", "web1.example.com"]);
    // Only the selected fields
    assert_eq!(
        all["nodes"][0],
        json!({"id": "root", "hostname": "server.example.com",
               "os": {"type": "Linux", "name": "Debian", "version": "13",
                      "fullName": "Debian GNU/Linux 13 (trixie)", "kernelVersion": "6.12.48"}})
    );

    let debian = list_nodes(json!({"where": [
        {"objectType": "node", "attribute": "osName", "comparator": "eq", "value": "Debian"}
    ], "composition": "or"}))
    .await;
    assert_eq!(debian["total"], 1);
    assert_eq!(debian["nodes"][0]["id"], "root");
}

#[tokio::test]
async fn get_node_without_the_global_policy_mode() {
    // `acl` can read the node but not the settings: the node anyway, with why the mode is raw
    let url = mcp_server(&mock_rudder().await).await;
    let node: Value =
        serde_json::from_str(&tool_text(&url, "acl", "get_node", json!({"node": "root"})).await)
            .unwrap();
    assert_eq!(node["policyMode"], "enforce");
    let reason = node["policyModeUnresolved"].as_str().unwrap();
    assert!(
        reason.contains("GET settings/global_policy_mode") && reason.contains("node_read"),
        "{reason}"
    );
}

const POLICY_NODE: &str = "12345678-0000-0000-0000-000000000001";
/// As Rudder writes it: an unquoted version, parameters by id
const SITE_YAML: &str = "id: site
name: Web site
version: 2.0
params:
  - id: 2628fa31-0c5b-4b4e-9d1f-7a1f5e7f0001
    name: path
    description: Site root
items:
  - name: Site file
    method: file_present
    params:
      path: ${site.path}
";

#[tokio::test]
async fn get_node_policies_overview_and_directive_details() {
    let url = mcp_server(&mock_rudder().await).await;
    let query = async |args| -> Value {
        serde_json::from_str(&tool_text(&url, "ro", "get_node_policies", args).await).unwrap()
    };
    let overview = query(json!({"node":POLICY_NODE})).await;
    assert_eq!(overview["rules"][0]["directives"][0]["id"], "d1");
    // The node's groups by name, its properties with where they are set
    assert_eq!(
        overview["groups"],
        json!([{"id": "g3", "name": "All nodes"}, {"id": "g2", "name": "Web servers"}])
    );
    assert_eq!(
        overview["properties"],
        json!([
            {"name": "env", "value": "prod", "provider": "inherited", "definedIn": [
                {"kind": "group", "id": "g2", "name": "Web servers"}, {"kind": "global"}]},
            {"name": "site", "value": {"root": "/srv/www"}, "description": "Site settings",
             "definedIn": [{"kind": "node", "id": POLICY_NODE, "name": "web"}]}
        ])
    );
    assert!(overview.get("propertiesError").is_none());
    let by_hostname = query(json!({"node":"server.example.com"})).await;
    assert_eq!(by_hostname["id"], "root");

    // Editor technique: its definition, and the variables named after its parameters
    let detail = query(json!({"node":POLICY_NODE,"directive":"d1"})).await;
    let sections = &detail["directive"]["parameters"]["section"]["sections"];
    assert_eq!(
        sections[0]["section"]["vars"],
        json!([{"var": {"name": "path", "value": "/srv/www"}},
               {"var": {"name": "UNKNOWN_PARAMETER", "value": "kept"}}])
    );
    assert_eq!(
        sections[1]["section"]["vars"][0]["var"]["name"],
        "expectedReportKey Site file"
    );
    assert_eq!(
        detail["technique"],
        json!({"id": "site", "name": "Web site", "version": "2.0", "source": "editor",
               "content": SITE_YAML})
    );

    // Built-in technique: the same shape, metadata only
    let builtin = query(json!({"node":POLICY_NODE,"directive":"d2"})).await;
    assert_eq!(
        builtin["technique"],
        json!({"id": "packages", "name": "Package management", "version": "2.0",
               "source": "built-in"})
    );
    assert_eq!(
        builtin["directive"]["parameters"]["section"]["sections"][0]["section"]["vars"][0]["var"]["name"],
        "PACKAGE_LIST"
    );
    let (failed, text) = tool_result(
        &url,
        Some("ro"),
        "get_node_policies",
        json!({"node":POLICY_NODE,"directive":"unrelated"}),
    )
    .await;
    assert!(failed && text.contains("not listed for node"), "{text}");
    let (failed, text) = tool_result(
        &url,
        Some("acl"),
        "get_node_policies",
        json!({"node":POLICY_NODE,"directive":"d1"}),
    )
    .await;
    assert!(failed && text.contains("technique_read"), "{text}");
    let (failed, text) = tool_result(
        &url,
        Some("acl"),
        "get_node_policies",
        json!({"node":POLICY_NODE}),
    )
    .await;
    assert!(failed && text.contains("group_read"), "{text}");
}

#[tokio::test]
async fn get_node_compliance_by_id_or_hostname() {
    let url = mcp_server(&mock_rudder().await).await;
    let expected = "- server.example.com (root): 50% [error 50%, successAlreadyOK 50%]\n\
                    \x20 - Web (r1): 50% [error 50%, successAlreadyOK 50%]";
    for node in ["root", "server.example.com"] {
        let text = tool_text(&url, "ro", "get_node_compliance", json!({"node": node})).await;
        assert_eq!(text, expected);
    }
    let (is_error, text) = tool_result(
        &url,
        Some("ro"),
        "get_node_compliance",
        json!({"node": "server"}),
    )
    .await;
    assert!(
        is_error && text.contains("Similar hostnames (id): server.example.com (root)."),
        "{text}"
    );
    let (is_error, text) = tool_result(
        &url,
        Some("acl"),
        "get_node_compliance",
        json!({"node": "root"}),
    )
    .await;
    assert!(
        is_error && text.contains("requires the compliance_read right"),
        "{text}"
    );
}

#[tokio::test]
async fn api_errors_are_tool_errors() {
    // The model sees every failed call and the right it needs, so it can tell the user
    let url = mcp_server(&mock_rudder().await).await;
    let (is_error, text) = tool_result(&url, Some("acl"), "get_server_info", json!({})).await;
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
async fn refuses_tool_calls_without_an_identified_token() {
    // The model is told why, so it can tell the user
    let url = mcp_server(&mock_rudder().await).await;
    for (token, expected) in [
        (None, "no Rudder API token"),
        (Some("unknown"), "Rudder rejected the API token"),
        (Some("narrow"), "must be allowed to read its own account"),
        (Some("anonymous"), "could not check the API token"),
        (Some("future"), "could not check the API token"),
    ] {
        let (is_error, text) = tool_result(&url, token, "get_current_account", json!({})).await;
        assert!(
            is_error && text.contains(expected),
            "token {token:?}: {text}"
        );
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
    let (is_error, text) = tool_result(&url, Some("ro"), "get_current_account", json!({})).await;
    assert!(
        is_error && text.contains("could not check the API token"),
        "{text}"
    );
    assert!(received.lock().unwrap().is_empty());
}

#[tokio::test]
async fn connects_without_token_while_rudder_is_unreachable() {
    // A port nothing listens on
    let closed = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let rudder_url = format!("http://{}", closed.local_addr().unwrap());
    drop(closed);
    let url = mcp_server(&rudder_url).await;

    // Discovering the server and listing the tools need neither
    let (status, body) = mcp_request(&url, None, "server/discover", None, json!({})).await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert!(body.contains(r#""tools":{"#), "{body}");
    let (status, body) = mcp_request(&url, None, "tools/list", None, json!({})).await;
    assert_eq!(status, StatusCode::OK, "{body}");
    let tools: Value = serde_json::from_str(&body).unwrap();
    assert!(
        tools["result"]["tools"]
            .as_array()
            .unwrap()
            .iter()
            .any(|tool| tool["name"] == "get_current_account"),
        "{body}"
    );
    // Tool calls are refused
    let (is_error, text) = tool_result(&url, Some("ro"), "get_current_account", json!({})).await;
    assert!(
        is_error && text.contains("could not check the API token"),
        "{text}"
    );
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
    tool_text(&url, "audit", "get_current_account", json!({})).await;
    call_tool(
        &url,
        Some("secret-unknown-token"),
        "get_current_account",
        json!({}),
    )
    .await;
    let legacy = reqwest::Client::new()
        .post(&url)
        .bearer_auth("legacy")
        .header("Accept", "application/json, text/event-stream")
        .json(&json!({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                      "params": {"name": "get_current_account", "arguments": {}}}))
        .send()
        .await
        .unwrap();
    assert_eq!(legacy.status(), StatusCode::OK);
    assert!(legacy.text().await.unwrap().contains("Account legacy"));
    let logs = logs();
    let line = |text: &str| {
        logs.lines()
            .find(|line| line.contains(text))
            .unwrap_or_else(|| panic!("no log line with '{text}' in:\n{logs}"))
    };

    let accepted =
        line(r#"account_name="Account audit" rights="rw"}: rudder_mcp::auth: MCP request"#);
    assert!(
        accepted.starts_with(" INFO mcp_request{client=127.0.0.1:"),
        "{accepted}"
    );
    assert!(
        accepted.contains(
            r#"mcp_method="tools/call" mcp_name="get_current_account" account="audit-id" "#
        ),
        "{accepted}"
    );
    assert!(accepted.contains("http_status=200"), "{accepted}");
    let legacy =
        line(r#"account_name="Account legacy" rights="rw"}: rudder_mcp::auth: MCP request"#);
    assert!(
        legacy.contains(r#"mcp_method="tools/call" mcp_name="get_current_account""#),
        "{legacy}"
    );
    // The whole fragment, span included: the log is shared by the concurrent tests, and those whose
    // request started before the subscriber was set log the same messages without their spans
    line(
        r#":token_check:rudder_api{method=GET path="apiaccounts/token" query=[]}: rudder_mcp::api: Rudder API answered status=200"#,
    );
    // Refusals have the request context, but no account
    assert!(
        logs.lines()
            .any(|line| line.contains(r#"mcp_name="get_current_account"}:"#)
                && line.contains("tool call refused rejection=Rejected")),
        "no refusal in a request span in:\n{logs}"
    );
    assert!(!logs.contains("secret-unknown-token"), "{logs}");
}
