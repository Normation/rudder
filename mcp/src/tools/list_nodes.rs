// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

//! The nodes, all or those matching a Rudder node search, to find the ones to look at with the
//! other tools.

use anyhow::Result;
use axum::http::request::Parts;
use rmcp::{
    handler::server::{tool::Extension, wrapper::Parameters},
    schemars, tool, tool_router,
};
use serde::{Deserialize, Serialize};
use serde_json::json;

use super::{Rudder, caller, node::nodes, tool_error};

#[derive(Debug, Deserialize, schemars::JsonSchema)]
#[serde(deny_unknown_fields)]
struct ListNodesQuery {
    /// Criteria of Rudder's node search. None: every node.
    #[serde(default, rename = "where")]
    criteria: Vec<Criterion>,
    /// How the criteria combine
    #[serde(default)]
    composition: Composition,
}

/// A criterion of Rudder's node search, passed as is: Rudder checks it, and its error lists the
/// valid object types, attributes or comparators for the model to retry with. Not a closed list
/// here, as the criteria change with Rudder versions and plugins.
#[derive(Debug, PartialEq, Eq, Serialize, Deserialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Criterion {
    /// e.g. `node`, `software`, `serializedNodeProperty`, `group`
    object_type: String,
    /// e.g. `nodeHostname`, `osName`, `cn` (software name)
    attribute: String,
    /// e.g. `eq`, `regex`, `exists`
    comparator: String,
    /// Not needed by `exists` and `notExists`
    #[serde(default, skip_serializing_if = "Option::is_none")]
    value: Option<String>,
}

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq, Deserialize, schemars::JsonSchema)]
#[serde(rename_all = "lowercase")]
enum Composition {
    /// Nodes matching every criterion
    #[default]
    And,
    /// Nodes matching any criterion
    Or,
}

impl Composition {
    /// Rudder's name
    fn as_str(self) -> &'static str {
        match self {
            Self::And => "and",
            Self::Or => "or",
        }
    }
}

/// API `include` value: what [`ListedNode`] needs
const INCLUDE: &str = "minimal,os";

/// A node as listed: enough to pick one, `node_info` gives the rest. Only these fields are read
/// and answered, whatever else Rudder sends now or adds later.
#[derive(Debug, PartialEq, Eq, Deserialize, Serialize)]
struct ListedNode {
    id: String,
    hostname: String,
    os: Os,
}

/// A node's OS, as Rudder names its fields. Rudder can add others to this object, some
/// Windows-only and sensitive (`productKey`): only these are passed on.
#[derive(Debug, PartialEq, Eq, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct Os {
    /// `Linux` or `Windows`
    #[serde(rename = "type")]
    os_type: String,
    name: String,
    version: String,
    full_name: String,
    kernel_version: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    service_pack: Option<String>,
}

#[tool_router(router = list_nodes_router, vis = "pub(super)")]
impl Rudder {
    #[tool(
        description = "Lists the accepted nodes (the Rudder server and relays included), all or those matching criteria of Rudder's node search: id, hostname and OS of each, sorted by hostname, with their number (`total`). Use `node_info` for a node's details. A criterion is `{objectType, attribute, comparator, value}`, e.g.: `node`: `nodeHostname`, `nodeId`, `osName` (`Debian`, `Ubuntu`, `Redhat`, `Centos`, `Suse`, `AlmaLinux`, `RockyLinux`, `Oracle`...), `OS` (`Linux`, `Windows`), `osVersion`, `osFullName`, `ipHostNumber`, `policyServerId`, `agentVersion`, `state` (`enabled`, `ignored`, `empty-policies`, `initializing`, `preparing-eol`); `software`: `cn` (name), `softwareVersion`; `serializedNodeProperty`: `name.value` (`eq` `key=value`, or `hasKey` `key`); `group`: `nodeGroupId` (`eq` a group id: its members). Comparators: `eq`, `notEq`, `regex`, `notRegex` (Java regex on the whole value), `exists`, `notExists` (no value), and `lt`, `lteq`, `gt`, `gteq` for versions, sizes and dates. An invalid criterion is an error listing the valid ones.",
        annotations(read_only_hint = true)
    )]
    async fn list_nodes(
        &self,
        Extension(parts): Extension<Parts>,
        Parameters(ListNodesQuery {
            criteria,
            composition,
        }): Parameters<ListNodesQuery>,
    ) -> Result<String, String> {
        let api = &caller(&parts)?.api;
        let condition = json!(criteria).to_string();
        let mut query = vec![("include", INCLUDE)];
        // Without criteria, Rudder answers every node, policy servers included
        if !criteria.is_empty() {
            query.extend([
                ("where", condition.as_str()),
                ("composition", composition.as_str()),
                // Otherwise the search leaves out the Rudder server and relays
                ("select", "nodeAndPolicyServer"),
            ]);
        }
        let body = api
            .get("nodes", &query, "node_read")
            .await
            .map_err(tool_error)?;
        format_nodes(nodes(&body).map_err(tool_error)?).map_err(tool_error)
    }
}

/// `{total, nodes}`: the nodes by hostname (then id, as hostnames can be shared). `total` spares
/// the model counting a long list.
fn format_nodes(mut nodes: Vec<ListedNode>) -> Result<String> {
    nodes.sort_by(|a, b| (&a.hostname, &a.id).cmp(&(&b.hostname, &b.id)));
    Ok(serde_json::to_string(
        &json!({"total": nodes.len(), "nodes": nodes}),
    )?)
}

#[cfg(test)]
mod tests {
    use pretty_assertions::assert_eq;

    use super::*;

    #[test]
    fn criteria_as_rudder_reads_them() {
        let query: ListNodesQuery = serde_json::from_value(json!({"where": [
            {"objectType": "software", "attribute": "cn", "comparator": "regex", "value": "openssl.*"},
            {"objectType": "serializedNodeProperty", "attribute": "name.value", "comparator": "exists"}
        ]}))
        .unwrap();
        assert_eq!(query.composition, Composition::And);
        // Built by serde: no value can change the query's structure
        assert_eq!(
            serde_json::to_string(&query.criteria).unwrap(),
            r#"[{"objectType":"software","attribute":"cn","comparator":"regex","value":"openssl.*"},{"objectType":"serializedNodeProperty","attribute":"name.value","comparator":"exists"}]"#
        );
        let typo = serde_json::from_value::<ListNodesQuery>(json!({"where": [
            {"objectType": "node", "attribute": "osName", "comparator": "eq", "valeu": "Debian"}
        ]}));
        assert!(typo.is_err());
    }

    /// The nodes listed from a `GET /nodes` body
    fn listed(nodes: serde_json::Value) -> serde_json::Value {
        let body = json!({"data": {"nodes": nodes}}).to_string();
        serde_json::from_str(&format_nodes(super::nodes(&body).unwrap()).unwrap()).unwrap()
    }

    #[test]
    fn nodes_sorted() {
        let os = json!({"type": "Linux", "name": "Debian", "version": "13",
                        "fullName": "Debian GNU/Linux 13 (trixie)", "kernelVersion": "6.12.48"});
        let node = |id: &str, hostname: &str| json!({"id": id, "hostname": hostname, "os": os});
        assert_eq!(
            listed(json!([
                node("b", "web"),
                node("root", "server"),
                node("a", "web")
            ])),
            json!({"total": 3, "nodes": [node("root", "server"), node("a", "web"), node("b", "web")]})
        );
    }

    #[test]
    fn only_selected_fields() {
        // Shaped as the `api_nodes.yml` webapp test answer, plus what Rudder could add
        let windows = json!([{
            "id": "w1", "hostname": "win.example.com", "status": "accepted", "newField": 1,
            "os": {"type": "Windows", "name": "Windows2019", "version": "10.0",
                   "fullName": "Windows Server 2019", "kernelVersion": "10.0.17763",
                   "servicePack": "1", "productKey": "XXXXX-XXXXX", "userDomain": "CORP"}
        }]);
        assert_eq!(
            listed(windows),
            json!({"total": 1, "nodes": [{"id": "w1", "hostname": "win.example.com",
                "os": {"type": "Windows", "name": "Windows2019", "version": "10.0",
                       "fullName": "Windows Server 2019", "kernelVersion": "10.0.17763",
                       "servicePack": "1"}}]})
        );
        // Without the OS asked for, Rudder's answer is not what we expect
        let body = json!({"data": {"nodes": [{"id": "a", "hostname": "web"}]}}).to_string();
        assert_eq!(
            super::nodes::<ListedNode>(&body).unwrap_err().to_string(),
            crate::tools::UNEXPECTED
        );
    }
}
