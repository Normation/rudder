// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

use serde_json::{Value, json};

use crate::router;

#[tokio::test]
async fn hello_answers() {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}/mcp", listener.local_addr().unwrap());
    tokio::spawn(async move { axum::serve(listener, router()).await.unwrap() });

    let request = json!({"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {
        "name": "hello",
        "arguments": {},
        "_meta": {
            "io.modelcontextprotocol/protocolVersion": "2026-07-28",
            "io.modelcontextprotocol/clientCapabilities": {}
        }
    }});
    let response: Value = reqwest::Client::new()
        .post(url)
        .header("Content-Type", "application/json")
        .header("Accept", "application/json, text/event-stream")
        .header("MCP-Protocol-Version", "2026-07-28")
        .header("Mcp-Method", "tools/call")
        .header("Mcp-Name", "hello")
        .body(request.to_string())
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(
        response["result"]["content"][0]["text"],
        "Hello from the Rudder MCP server"
    );
}
