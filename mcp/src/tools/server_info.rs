// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

//! Rudder server information, from the Rudder API

use anyhow::{Context, Result};
use axum::http::request::Parts;
use rmcp::{handler::server::tool::Extension, tool, tool_router};
use serde::Deserialize;
use serde_json::{Map, Value};

use super::{Rudder, caller, tool_error};

const UNEXPECTED: &str = "unexpected Rudder API response";

#[tool_router(router = server_info_router, vis = "pub(super)")]
impl Rudder {
    #[tool(
        description = "Rudder server information: Rudder version, instance id, relays, OS, node counts by policy mode, installed plugins, webapp status, and global compliance as the percentage of each report status (empty when no policies are defined)",
        annotations(read_only_hint = true)
    )]
    async fn server_info(&self, Extension(parts): Extension<Parts>) -> Result<String, String> {
        let api = &caller(&parts)?.api;
        let (info, status, compliance) = tokio::join!(
            api.get("system/info"),
            api.get("system/status"),
            api.get("compliance")
        );
        format_info(
            &info.map_err(tool_error)?,
            &status.map_err(tool_error)?,
            &compliance.map_err(tool_error)?,
        )
        .map_err(tool_error)
    }
}

/// Server information, from the bodies of `GET /system/info`, `GET /system/status` and
/// `GET /compliance`: the info data, without the JVM, plus the webapp `status` and the global
/// `compliance` details (percentage by report status, nothing without policies). The JVM's command line is most of the response,
/// only noise for the model, and it exposes the server's internal paths and settings. Other fields
/// are kept as is, so new ones come through.
fn format_info(info_body: &str, status_body: &str, compliance_body: &str) -> Result<String> {
    #[derive(Deserialize)]
    struct Response<T> {
        data: T,
    }
    #[derive(Deserialize)]
    struct Status {
        global: Value,
    }
    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct Compliance {
        global_compliance: GlobalCompliance,
    }
    /// The global rate is left out: the details say more
    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct GlobalCompliance {
        /// Absent without policies (rate `-1`)
        #[serde(default)]
        compliance_details: Map<String, Value>,
    }
    fn data<T: serde::de::DeserializeOwned>(body: &str) -> Result<T> {
        Ok(serde_json::from_str::<Response<T>>(body)
            .context(UNEXPECTED)?
            .data)
    }

    let mut info: Map<String, Value> = data(info_body)?;
    let status: Status = data(status_body)?;
    let compliance: Compliance = data(compliance_body)?;
    if let Some(system) = info.get_mut("system").and_then(Value::as_object_mut) {
        system.remove("jvm");
    }
    info.insert("status".to_owned(), status.global);
    info.insert(
        "compliance".to_owned(),
        Value::Object(compliance.global_compliance.compliance_details),
    );
    Ok(serde_json::to_string(&info)?)
}

#[cfg(test)]
mod tests {
    use pretty_assertions::assert_eq;
    use serde_json::json;

    use super::*;

    #[test]
    fn info_with_status_and_compliance_without_jvm() {
        // Shape of real 9.2 responses, command line shortened
        let info_body = json!({"action": "getSystemInfo", "result": "success", "data": {
            "rudder": {"version": "9.2.0", "buildTime": "2026-09-23T09:49:42Z",
                       "instanceId": "0523bc78-4529-4d99-a375-15f8c75c0ec1", "relays": []},
            "system": {"os": {"name": "Debian GNU/Linux 13 (trixie)", "version": "13"},
                       "jvm": {"version": "21.0.12.1", "cmd": "/usr/lib/jvm/java-21/bin/java -Xmx1024m ..."}},
            "nodes": {"total": 1, "audit": 0, "enforce": 0, "mixed": 0, "enabled": 1, "disabled": 0},
            "plugins": []
        }})
        .to_string();
        let status = r#"{"action":"getStatus","result":"success","data":{"global":"OK"}}"#;
        let compliance = r#"{"action":"getGlobalCompliance","result":"success","data":{"globalCompliance":{
            "compliance":83,"complianceDetails":{"successAlreadyOK":83.33,"error":16.67}}}}"#;
        let info: Value =
            serde_json::from_str(&format_info(&info_body, status, compliance).unwrap()).unwrap();
        assert_eq!(
            info["system"],
            json!({"os": {"name": "Debian GNU/Linux 13 (trixie)", "version": "13"}})
        );
        assert_eq!(info["rudder"]["version"], "9.2.0");
        assert_eq!(info["nodes"]["total"], 1);
        assert_eq!(info["status"], "OK");
        assert_eq!(
            info["compliance"],
            json!({"successAlreadyOK": 83.33, "error": 16.67})
        );
    }

    #[test]
    fn unexpected_responses() {
        let status = r#"{"data":{"global":"OK"}}"#;
        let compliance = r#"{"data":{"globalCompliance":{"compliance":-1}}}"#;
        let error = |info_body, status, compliance| {
            format_info(info_body, status, compliance)
                .unwrap_err()
                .to_string()
        };
        assert_eq!(error("{}", status, compliance), UNEXPECTED);
        assert_eq!(error(r#"{"data":{}}"#, "{}", compliance), UNEXPECTED);
        assert_eq!(
            error(r#"{"data":{}}"#, status, r#"{"data":{}}"#),
            UNEXPECTED
        );
    }
}
