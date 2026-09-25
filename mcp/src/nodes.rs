//! Node information from the Rudder API.

use axum::http::{Method, request::Parts};
use rmcp::schemars;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

use crate::RudderApi;

/// How the caller designates a node
#[derive(Debug, PartialEq, Eq)]
enum NodeRef<'a> {
    Id(&'a str),
    Hostname(&'a str),
}

impl<'a> NodeRef<'a> {
    /// Node ids are UUIDs, or `root` for the Rudder server. Only those go into the URL path:
    /// anything else is looked up as a hostname, sent as an encoded query value.
    fn parse(node: &'a str) -> Self {
        let is_uuid = node.len() == 36
            && node.chars().enumerate().all(|(i, c)| match i {
                8 | 13 | 18 | 23 => c == '-',
                _ => c.is_ascii_hexdigit(),
            });
        if node == "root" || is_uuid {
            Self::Id(node)
        } else {
            Self::Hostname(node)
        }
    }
}

/// Inventory sections beyond the default level (API `include` values of the `full` level)
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, schemars::JsonSchema)]
#[serde(rename_all = "camelCase")]
pub enum InventorySection {
    Accounts,
    Bios,
    Controllers,
    EnvironmentVariables,
    FileSystems,
    InstanceId,
    ManagementTechnologyDetails,
    Memories,
    NetworkInterfaces,
    Ports,
    Processes,
    Processors,
    Slots,
    Software,
    SoftwareUpdate,
    Sound,
    Storage,
    Videos,
    VirtualMachines,
}

/// API path and query parameters to get `node` with the default inventory level plus `details`.
/// Both lookups return the `data.nodes` array.
pub fn request(node: &str, details: &[InventorySection]) -> (String, Vec<(&'static str, String)>) {
    let mut include = "default".to_owned();
    for section in details {
        // The serialized name is the API `include` value
        if let Value::String(name) = json!(section) {
            include.push(',');
            include.push_str(&name);
        }
    }
    lookup(node, include)
}

/// Node id of `node`: as is for an id, looked up (one API call) for a hostname
pub async fn resolve_id(api: &RudderApi, parts: &Parts, node: &str) -> Result<String, String> {
    match NodeRef::parse(node) {
        NodeRef::Id(id) => Ok(id.to_owned()),
        NodeRef::Hostname(_) => {
            let (path, query) = lookup(node, "minimal".to_owned());
            let query: Vec<(&str, &str)> = query.iter().map(|(k, v)| (*k, v.as_str())).collect();
            let body = api.call(parts, Method::GET, &path, &query).await?;
            select_one(&body, node)?["id"]
                .as_str()
                .map(str::to_owned)
                .ok_or_else(|| "unexpected Rudder API response: node without id".to_owned())
        }
    }
}

fn lookup(node: &str, include: String) -> (String, Vec<(&'static str, String)>) {
    match NodeRef::parse(node) {
        NodeRef::Id(id) => (format!("nodes/{id}"), vec![("include", include)]),
        NodeRef::Hostname(hostname) => {
            let query = json!([{
                "objectType": "node",
                "attribute": "nodeHostname",
                "comparator": "eq",
                "value": hostname,
            }]);
            (
                "nodes".to_owned(),
                vec![
                    ("include", include),
                    ("where", query.to_string()),
                    // Otherwise queries leave out the Rudder server and relays
                    ("select", "nodeAndPolicyServer".to_owned()),
                ],
            )
        }
    }
}

/// Extracts the single node from the API response, as JSON for the model
pub fn select(body: &str, node: &str) -> Result<String, String> {
    serde_json::to_string_pretty(&select_one(body, node)?).map_err(|e| e.to_string())
}

fn select_one(body: &str, node: &str) -> Result<Value, String> {
    #[derive(Deserialize)]
    struct Response {
        data: Data,
    }
    #[derive(Deserialize)]
    struct Data {
        nodes: Vec<Value>,
    }

    let mut nodes = serde_json::from_str::<Response>(body)
        .map_err(|e| format!("unexpected Rudder API response: {e}"))?
        .data
        .nodes;
    match nodes.len() {
        0 => Err(format!(
            "no node found for '{node}': give a node id, or its exact hostname as in the inventory (usually fully qualified)"
        )),
        1 => Ok(nodes.remove(0)),
        _ => {
            let ids: Vec<&str> = nodes.iter().filter_map(|n| n["id"].as_str()).collect();
            Err(format!(
                "several nodes have the hostname '{node}', use one of their ids: {}",
                ids.join(", ")
            ))
        }
    }
}

#[cfg(test)]
mod tests {
    use pretty_assertions::assert_eq;

    use super::*;

    #[test]
    fn node_ids_and_hostnames() {
        let id = "8b168194-c0b4-41ab-b2b5-9571a8906d59";
        assert_eq!(NodeRef::parse(id), NodeRef::Id(id));
        assert_eq!(
            NodeRef::parse("8B168194-C0B4-41AB-B2B5-9571A8906D59"),
            NodeRef::Id("8B168194-C0B4-41AB-B2B5-9571A8906D59")
        );
        assert_eq!(NodeRef::parse("root"), NodeRef::Id("root"));
        assert_eq!(
            NodeRef::parse("node1.example.com"),
            NodeRef::Hostname("node1.example.com")
        );
        // Never reaches the URL path
        assert_eq!(
            NodeRef::parse("../system/info"),
            NodeRef::Hostname("../system/info")
        );
        assert_eq!(
            NodeRef::parse("8b168194-c0b4-41ab-b2b5-9571a8906d5/"),
            NodeRef::Hostname("8b168194-c0b4-41ab-b2b5-9571a8906d5/")
        );
    }

    #[test]
    fn request_by_id_with_details() {
        let (path, query) = request(
            "root",
            &[InventorySection::Software, InventorySection::FileSystems],
        );
        assert_eq!(path, "nodes/root");
        assert_eq!(
            query,
            [("include", "default,software,fileSystems".to_owned())]
        );
    }

    #[test]
    fn request_by_hostname() {
        let (path, query) = request("node1.example.com", &[]);
        assert_eq!(path, "nodes");
        assert_eq!(query[0], ("include", "default".to_owned()));
        let condition: Value = serde_json::from_str(&query[1].1).unwrap();
        assert_eq!(
            condition,
            json!([{"objectType": "node", "attribute": "nodeHostname", "comparator": "eq", "value": "node1.example.com"}])
        );
        assert_eq!(query[2], ("select", "nodeAndPolicyServer".to_owned()));
    }

    #[test]
    fn select_single_node() {
        let body = r#"{"result":"success","data":{"nodes":[{"id":"root","hostname":"server"}]}}"#;
        let node: Value = serde_json::from_str(&select(body, "root").unwrap()).unwrap();
        assert_eq!(node, json!({"id": "root", "hostname": "server"}));
    }

    #[test]
    fn select_none_or_ambiguous() {
        let error = select(r#"{"data":{"nodes":[]}}"#, "web").unwrap_err();
        assert!(error.contains("no node found for 'web'"), "{error}");
        let error = select(r#"{"data":{"nodes":[{"id":"a"},{"id":"b"}]}}"#, "web").unwrap_err();
        assert!(error.contains("use one of their ids: a, b"), "{error}");
    }
}
