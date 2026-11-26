// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

//! Designating a node, by id or hostname: the same for every tool taking a node. See `DESIGN.md`
//! (Designating a node).

use anyhow::{Context, Result, bail};
use serde::{Deserialize, de::DeserializeOwned};
use serde_json::{Value, json};

use super::{UNEXPECTED, data, tool_error};
use crate::api::CallerApi;

/// How the caller designates a node
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(super) enum NodeRef<'a> {
    Id(&'a str),
    Hostname(&'a str),
}

impl<'a> NodeRef<'a> {
    /// Node ids are UUIDs, or `root` for the Rudder server. Only those go into the URL path:
    /// anything else is looked up as a hostname, sent as an encoded query value.
    pub(super) fn parse(node: &'a str) -> Self {
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

    /// As the caller gave it
    fn as_str(self) -> &'a str {
        match self {
            Self::Id(node) | Self::Hostname(node) => node,
        }
    }

    /// Body of `GET /nodes/{id}`, or of a search on the exact hostname, at the `include` detail
    /// level: both answer a `nodes` array, read with [`Self::find`]
    pub(super) async fn get(self, api: &CallerApi, include: &str) -> Result<String> {
        match self {
            Self::Id(id) => {
                api.get(&format!("nodes/{id}"), &[("include", include)], "node_read")
                    .await
            }
            Self::Hostname(hostname) => search_hostname(api, include, "eq", hostname).await,
        }
    }

    /// The node in `body`, an answer of [`Self::get`]. None or several is a tool error telling the
    /// model what to give instead.
    pub(super) async fn find(self, api: &CallerApi, body: &str) -> Result<Value, String> {
        let nodes = nodes(body).map_err(tool_error)?;
        match single_node(nodes, self.as_str()).map_err(tool_error)? {
            Some(found) => Ok(found),
            None => {
                let similar = match self {
                    Self::Hostname(hostname) => similar_hostnames(api, hostname).await,
                    Self::Id(_) => Vec::new(),
                };
                Err(not_found(self.as_str(), &similar))
            }
        }
    }

    /// The node's id: as given, or that of the node with this hostname, which costs a search
    pub(super) async fn id(self, api: &CallerApi) -> Result<String, String> {
        match self {
            Self::Id(id) => Ok(id.to_owned()),
            Self::Hostname(_) => {
                let body = self.get(api, "minimal").await.map_err(tool_error)?;
                let found = self.find(api, &body).await?;
                found["id"]
                    .as_str()
                    .map(str::to_owned)
                    .context(UNEXPECTED)
                    .map_err(tool_error)
            }
        }
    }
}

/// Body of a node search on the hostname, `comparator` being a Rudder one (`eq`, `regex`)
async fn search_hostname(
    api: &CallerApi,
    include: &str,
    comparator: &str,
    hostname: &str,
) -> Result<String> {
    let condition = json!([{
        "objectType": "node",
        "attribute": "nodeHostname",
        "comparator": comparator,
        "value": hostname,
    }])
    .to_string();
    let query = [
        ("include", include),
        ("where", &condition),
        // Otherwise the search leaves out the Rudder server and relays
        ("select", "nodeAndPolicyServer"),
    ];
    api.get("nodes", &query, "node_read").await
}

/// Most similar nodes listed when a hostname matches none
const MAX_SIMILAR: usize = 10;

/// The nodes whose hostname has the same first label as `hostname`, in any case and domain: the
/// usual near misses, a short name for a fully qualified one or the reverse, another domain, a
/// different case. Only a help for the error message: a failed search gives none.
async fn similar_hostnames(api: &CallerApi, hostname: &str) -> Vec<Value> {
    let Some(regex) = similar_hostnames_regex(hostname) else {
        return Vec::new();
    };
    // `minimal`: the id and hostname are all we list
    match search_hostname(api, "minimal", "regex", &regex)
        .await
        .and_then(|body| nodes(&body))
    {
        Ok(nodes) => nodes,
        Err(error) => {
            tracing::debug!("similar hostnames search failed: {error:#}");
            Vec::new()
        }
    }
}

/// Rudder (Java, whole value) regex matching the hostnames with the same first label as
/// `hostname`, case-insensitive. Only for a label of hostname characters, so nothing needs
/// escaping and no regex syntax from the caller reaches Rudder.
fn similar_hostnames_regex(hostname: &str) -> Option<String> {
    let label = hostname.split('.').next()?;
    let valid = !label.is_empty()
        && label
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_');
    valid.then(|| format!(r"(?i){label}(\..*)?"))
}

/// Error for a node found nowhere, with the `similar` ones (from `GET /nodes`) to retry with
fn not_found(node: &str, similar: &[Value]) -> String {
    let exact = format!("no node found for '{node}'");
    if similar.is_empty() {
        return format!(
            "{exact}: give a node id, or its exact hostname as in the inventory (usually fully qualified)"
        );
    }
    let listed: Vec<String> = similar
        .iter()
        .take(MAX_SIMILAR)
        .map(|n| {
            format!(
                "{} ({})",
                n["hostname"].as_str().unwrap_or_default(),
                n["id"].as_str().unwrap_or_default()
            )
        })
        .collect();
    let more = match similar.len().saturating_sub(MAX_SIMILAR) {
        0 => String::new(),
        more => format!(", and {more} more"),
    };
    format!(
        "{exact}, the hostname must be exact. Similar hostnames (id): {}{more}. Retry with one of them if it is the node meant",
        listed.join(", ")
    )
}

/// The nodes of a `GET /nodes` or `GET /nodes/{id}` body (both answer a `nodes` array), as
/// `Value` or a type selecting their fields
pub(super) fn nodes<T: DeserializeOwned>(body: &str) -> Result<Vec<T>> {
    #[derive(Deserialize)]
    struct Nodes<T> {
        nodes: Vec<T>,
    }
    Ok(data::<Nodes<T>>(body)?.nodes)
}

/// The node designated by `node`, `None` when there is none. Several (the same hostname) is an
/// error listing their ids.
fn single_node(mut nodes: Vec<Value>, node: &str) -> Result<Option<Value>> {
    match nodes.len() {
        0 => Ok(None),
        1 => Ok(Some(nodes.remove(0))),
        _ => {
            let ids: Vec<&str> = nodes.iter().filter_map(|n| n["id"].as_str()).collect();
            bail!(
                "several nodes have the hostname '{node}', use one of their ids: {}",
                ids.join(", ")
            )
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
        let upper = "8B168194-C0B4-41AB-B2B5-9571A8906D59";
        assert_eq!(NodeRef::parse(upper), NodeRef::Id(upper));
        assert_eq!(NodeRef::parse("root"), NodeRef::Id("root"));
        assert_eq!(
            NodeRef::parse("node1.example.com"),
            NodeRef::Hostname("node1.example.com")
        );
        // Never reach the URL path
        for hostname in ["../system/info", "8b168194-c0b4-41ab-b2b5-9571a8906d5/"] {
            assert_eq!(NodeRef::parse(hostname), NodeRef::Hostname(hostname));
        }
    }

    #[test]
    fn no_node_or_several() {
        let single = |body| single_node(nodes(body).unwrap(), "web");
        assert_eq!(single(r#"{"data":{"nodes":[]}}"#).unwrap(), None);
        let several = single(r#"{"data":{"nodes":[{"id":"a"},{"id":"b"}]}}"#)
            .unwrap_err()
            .to_string();
        assert!(several.ends_with("use one of their ids: a, b"), "{several}");
        assert_eq!(nodes::<Value>("{}").unwrap_err().to_string(), UNEXPECTED);
    }

    #[test]
    fn similar_hostnames_by_first_label() {
        let regex = similar_hostnames_regex("Web-1.example.com").unwrap();
        assert_eq!(regex, r"(?i)Web-1(\..*)?");
        assert_eq!(
            similar_hostnames_regex("web_1").unwrap(),
            r"(?i)web_1(\..*)?"
        );
        // Nothing that could be regex syntax reaches Rudder
        for hostname in ["", ".example.com", "web*", "a|b", "(?s)x", "wéb", "web 1"] {
            assert_eq!(similar_hostnames_regex(hostname), None, "{hostname}");
        }
    }

    #[test]
    fn not_found_lists_similar_nodes() {
        let none = not_found("web", &[]);
        assert!(
            none.starts_with("no node found for 'web': give a node id"),
            "{none}"
        );
        let similar = not_found(
            "web1",
            &[json!({"id": "a", "hostname": "web1.example.com", "status": "accepted"})],
        );
        assert_eq!(
            similar,
            "no node found for 'web1', the hostname must be exact. Similar hostnames (id): web1.example.com (a). Retry with one of them if it is the node meant"
        );
        let many: Vec<Value> = (0..MAX_SIMILAR + 2)
            .map(|i| json!({"id": i.to_string(), "hostname": format!("web1.dc{i}")}))
            .collect();
        let error = not_found("web1", &many);
        assert!(error.contains("web1.dc9 (9), and 2 more."), "{error}");
        assert!(!error.contains("dc10"), "{error}");
    }
}
