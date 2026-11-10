// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

//! A node's configuration and inventory, from the Rudder API. Its runtime state (compliance,
//! reports) is left to other tools.

use anyhow::{Context, Result, bail};
use axum::http::request::Parts;
use rmcp::{
    handler::server::{tool::Extension, wrapper::Parameters},
    schemars, tool, tool_router,
};
use serde::Deserialize;
use serde_json::{Value, json};

use super::{
    Rudder, UNEXPECTED, caller,
    policy_mode::{GlobalPolicyMode, PolicyMode},
    tool_error,
};
use crate::api::CallerApi;

#[derive(Debug, Deserialize, schemars::JsonSchema)]
#[serde(deny_unknown_fields)]
struct NodeQuery {
    /// Node id (UUID, or `root` for the Rudder server), or hostname exactly as in the inventory
    node: String,
    /// Inventory sections to add to the default information, by their Rudder API name, e.g.
    /// `fileSystems`, `networkInterfaces`, `processors`, `memories`, `storage`, `bios`,
    /// `virtualMachines`, `environmentVariables`, `accounts`, `ports`, `processes`. Some can be
    /// large (processes): only request what is needed. Rudder ignores the names it does not know.
    #[serde(default)]
    details: Vec<String>,
    /// The installed software and available updates whose name contains this text
    /// (case-insensitive), e.g. `openssl`. The only way to get software: the whole list is too
    /// large.
    software: Option<String>,
}

/// How the caller designates a node, the same for every tool taking one: see `DESIGN.md`
/// (Designating a node)
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

/// An inventory section beyond the default level, by its Rudder API name (an `include` value).
/// Not a closed list: Rudder's sections change with its versions, and it ignores unknown names.
/// Only refused: what is not a plain name (a `,` would add other `include` values), the detail
/// levels (`full` is every section), and the sections given otherwise or not at all.
#[derive(Debug, PartialEq, Eq)]
struct InventorySection(String);

impl InventorySection {
    fn parse(name: &str) -> Result<Self> {
        if name.is_empty() || !name.chars().all(|c| c.is_ascii_alphanumeric()) {
            bail!("'{name}' is not an inventory section name, e.g. `fileSystems`");
        }
        match name.to_lowercase().as_str() {
            // Rudder sends the updates with the software
            "software" | "softwareupdate" => {
                bail!("the software is not a section: use the `software` filter")
            }
            // The agent's certificate and user, nothing to tell the user about
            "managementtechnologydetails" => bail!("'{name}' is not available"),
            "minimal" | "default" | "full" => {
                bail!("'{name}' is a detail level, not a section: name the sections needed")
            }
            _ => Ok(Self(name.to_owned())),
        }
    }
}

/// Most packages a software filter may match, in each of the installed and update lists: past
/// that, the answer is no longer about one piece of software, and takes much of the context
const MAX_PACKAGES: usize = 100;

/// A non-empty software name filter, lowercased for a case-insensitive match. Empty would match
/// every package: the whole list, which the filter is there to avoid.
#[derive(Debug, PartialEq, Eq)]
struct SoftwareFilter(String);

impl SoftwareFilter {
    fn parse(filter: &str) -> Result<Self> {
        let filter = filter.trim();
        if filter.is_empty() {
            bail!("the software filter is empty: give a name, e.g. `openssl`");
        }
        Ok(Self(filter.to_lowercase()))
    }

    fn matches(&self, name: &str) -> bool {
        name.to_lowercase().contains(&self.0)
    }
}

#[tool_router(router = node_info_router, vis = "pub(super)")]
impl Rudder {
    #[tool(
        description = "A node's configuration and inventory, by id or hostname: OS, IP addresses, RAM, policy server, agent, policy mode, node properties, state, acceptance, last run and inventory dates. Other inventory sections on request, and installed software and available updates filtered by name (e.g. which openssl version a node runs). No compliance. `policyMode` is the mode that applies to the node, `policyModeSource` where it comes from: `node` (its own setting), `global` (it follows the global mode), `forced` (the global mode cannot be overridden); directives can also set their own mode, not accounted for here. `status` is the node's acceptance in Rudder (`accepted`); `state` is how Rudder manages it: `enabled`; `empty-policies` (only Rudder's system policies); `ignored` (disabled); `initializing` and `preparing-eol` (labels, managed as `enabled`).",
        annotations(read_only_hint = true)
    )]
    async fn node_info(
        &self,
        Extension(parts): Extension<Parts>,
        Parameters(NodeQuery {
            node,
            details,
            software,
        }): Parameters<NodeQuery>,
    ) -> Result<String, String> {
        let api = &caller(&parts)?.api;
        let details = details
            .iter()
            .map(|name| InventorySection::parse(name))
            .collect::<Result<Vec<_>>>()
            .map_err(tool_error)?;
        let software = software
            .as_deref()
            .map(SoftwareFilter::parse)
            .transpose()
            .map_err(tool_error)?;
        let include = include(&details, software.is_some());
        let node_ref = NodeRef::parse(&node);
        let node_body = async {
            match node_ref {
                NodeRef::Id(id) => {
                    api.get(
                        &format!("nodes/{id}"),
                        &[("include", &include)],
                        "node_read",
                    )
                    .await
                }
                NodeRef::Hostname(hostname) => search_hostname(api, &include, "eq", hostname).await,
            }
        };
        let (body, global) = tokio::join!(node_body, GlobalPolicyMode::fetch(api));
        let nodes = body.and_then(|body| nodes(&body)).map_err(tool_error)?;
        let found = match single_node(nodes, &node).map_err(tool_error)? {
            Some(found) => found,
            None => {
                let similar = match node_ref {
                    NodeRef::Hostname(hostname) => similar_hostnames(api, hostname).await,
                    NodeRef::Id(_) => Vec::new(),
                };
                return Err(not_found(&node, &similar));
            }
        };
        format_node(found, software.as_ref(), global).map_err(tool_error)
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

/// API `include` value: the default level plus `details`, and the software (with its updates)
/// for a software filter
fn include(details: &[InventorySection], software: bool) -> String {
    let mut include = "default".to_owned();
    for section in details {
        include.push(',');
        include.push_str(&section.0);
    }
    if software {
        include.push_str(",software");
    }
    include
}

/// The nodes of a `GET /nodes` or `GET /nodes/{id}` body (both answer a `nodes` array)
fn nodes(body: &str) -> Result<Vec<Value>> {
    #[derive(Deserialize)]
    struct Response {
        data: Data,
    }
    #[derive(Deserialize)]
    struct Data {
        nodes: Vec<Value>,
    }
    Ok(serde_json::from_str::<Response>(body)
        .context(UNEXPECTED)?
        .data
        .nodes)
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

/// The node as Rudder sends it, without what only adds noise (see [`trim`]), with the policy mode
/// that applies (see [`resolve_policy_mode`]). With `software`, only the matching packages, both
/// installed (`software`) and available updates (`softwareUpdate`).
fn format_node(
    mut found: Value,
    software: Option<&SoftwareFilter>,
    global: Result<GlobalPolicyMode>,
) -> Result<String> {
    trim(&mut found);
    resolve_policy_mode(&mut found, global);
    if let Some(filter) = software {
        for section in ["software", "softwareUpdate"] {
            // `get_mut`, not `found[section]`: mutable indexing would insert a `null` field
            if let Some(packages) = found.get_mut(section).and_then(Value::as_array_mut) {
                packages.retain(|package| {
                    package["name"]
                        .as_str()
                        .is_some_and(|name| filter.matches(name))
                });
                if packages.len() > MAX_PACKAGES {
                    bail!(
                        "{} packages in `{section}` match '{}': give a more specific software name",
                        packages.len(),
                        filter.0
                    );
                }
            }
        }
    }
    Ok(serde_json::to_string(&found)?)
}

/// Replaces Rudder's `policyMode`, the node's own setting (`default` without one), by the mode
/// that applies, and adds where it comes from (`policyModeSource`). Without the global mode (e.g.
/// an ACL token without the settings paths), keeps Rudder's value and says why
/// (`policyModeUnresolved`): the rest of the node is still worth answering.
fn resolve_policy_mode(node: &mut Value, global: Result<GlobalPolicyMode>) {
    // Rudder always sends it at the default level: without it, nothing to resolve
    let Some(node) = node
        .as_object_mut()
        .filter(|n| n.contains_key("policyMode"))
    else {
        return;
    };
    let resolved =
        global.and_then(|global| Ok(global.apply_to(PolicyMode::parse_own(&node["policyMode"])?)));
    match resolved {
        Ok((mode, source)) => {
            node.insert("policyMode".to_owned(), json!(mode));
            node.insert("policyModeSource".to_owned(), json!(source));
        }
        Err(error) => {
            let reason = format!(
                "the global policy mode could not be read, `default` means the node follows it: {error:#}"
            );
            node.insert("policyModeUnresolved".to_owned(), json!(reason));
        }
    }
}

/// Removes the fields that cost context without telling the model anything: the inventory's
/// internal machine id, the agent's capabilities (internal feature flags), and Rudder's
/// placeholder for an unknown agent version (absent says the same).
fn trim(node: &mut Value) {
    if let Some(machine) = node.get_mut("machine").and_then(Value::as_object_mut) {
        machine.remove("id");
    }
    if let Some(agents) = node
        .get_mut("managementTechnology")
        .and_then(Value::as_array_mut)
    {
        for agent in agents.iter_mut().filter_map(Value::as_object_mut) {
            agent.remove("capabilities");
            if agent.get("version").and_then(Value::as_str) == Some("Missing version") {
                agent.remove("version");
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use pretty_assertions::assert_eq;

    use super::*;

    /// The single node of a `GET /nodes` body
    fn only(body: &str) -> Value {
        nodes(body).unwrap().remove(0)
    }

    /// Global mode `audit`, overridable
    fn global() -> Result<GlobalPolicyMode> {
        Ok(GlobalPolicyMode {
            mode: PolicyMode::Audit,
            overridable: true,
        })
    }

    #[test]
    fn policy_mode_resolved_in_node() {
        let resolve = |mode: &str, global| {
            let mut node = json!({"id": "root", "policyMode": mode});
            resolve_policy_mode(&mut node, global);
            node
        };
        assert_eq!(
            resolve("default", global()),
            json!({"id": "root", "policyMode": "audit", "policyModeSource": "global"})
        );
        assert_eq!(
            resolve("enforce", global()),
            json!({"id": "root", "policyMode": "enforce", "policyModeSource": "node"})
        );
        // Without the global mode, Rudder's value and why
        let node = resolve(
            "default",
            Err(anyhow::anyhow!("requires the node_read right")),
        );
        assert_eq!(node["policyMode"], "default");
        assert!(node.get("policyModeSource").is_none());
        let reason = node["policyModeUnresolved"].as_str().unwrap();
        assert!(reason.ends_with("requires the node_read right"), "{reason}");
        // A mode this version does not know
        let node = resolve("strict", global());
        assert_eq!(node["policyMode"], "strict");
        assert!(
            node["policyModeUnresolved"]
                .as_str()
                .unwrap()
                .contains(UNEXPECTED)
        );
        // Nothing to resolve
        let mut node = json!({"id": "root"});
        resolve_policy_mode(&mut node, global());
        assert_eq!(node, json!({"id": "root"}));
    }

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
    fn inventory_sections() {
        // Any plain name, Rudder ignoring those it does not know
        for name in ["fileSystems", "newSectionOf2030"] {
            assert_eq!(
                InventorySection::parse(name).unwrap(),
                InventorySection(name.to_owned())
            );
        }
        let refused = |name| InventorySection::parse(name).unwrap_err().to_string();
        // Nothing else reaches `include`
        for name in ["", "fileSystems,software", "file systems", "processes;"] {
            assert!(
                refused(name).contains("not an inventory section name"),
                "{name}"
            );
        }
        for name in ["software", "SoftwareUpdate"] {
            assert!(
                refused(name).contains("use the `software` filter"),
                "{name}"
            );
        }
        assert!(refused("managementTechnologyDetails").contains("not available"));
        for name in ["full", "Minimal", "default"] {
            assert!(refused(name).contains("detail level"), "{name}");
        }
    }

    #[test]
    fn include_sections() {
        assert_eq!(include(&[], false), "default");
        assert_eq!(
            include(
                &[
                    InventorySection::parse("fileSystems").unwrap(),
                    InventorySection::parse("processes").unwrap()
                ],
                true
            ),
            "default,fileSystems,processes,software"
        );
    }

    #[test]
    fn single_node_passed_through() {
        let body = r#"{"result":"success","data":{"nodes":[{"id":"root","hostname":"server","properties":[]}]}}"#;
        assert_eq!(
            format_node(only(body), None, global()).unwrap(),
            r#"{"hostname":"server","id":"root","properties":[]}"#
        );
    }

    #[test]
    fn software_filtered_by_name() {
        // Real 9.2 inventory entries
        let body = json!({"data": {"nodes": [{"id": "root", "software": [
            {"name": "openssl", "version": "3.5.1-1+deb13u1"},
            {"name": "libssl3t64", "version": "3.5.1-1+deb13u1"},
            {"name": "OpenSSL-provider-legacy", "version": "3.5.1-1+deb13u1"},
            {"name": "bash", "version": "5.2.37-2"}
        ], "softwareUpdate": [
            {"name": "openssl", "version": "3.5.7-1~deb13u2", "kind": "security"},
            {"name": "python3-requests", "version": "2.32.3+dfsg-5+deb13u1", "kind": "none"}
        ]}]}})
        .to_string();
        let filter = SoftwareFilter::parse(" OpenSSL ").unwrap();
        let node: Value =
            serde_json::from_str(&format_node(only(&body), Some(&filter), global()).unwrap())
                .unwrap();
        let names: Vec<&str> = node["software"]
            .as_array()
            .unwrap()
            .iter()
            .map(|p| p["name"].as_str().unwrap())
            .collect();
        assert_eq!(names, ["openssl", "OpenSSL-provider-legacy"]);
        assert_eq!(
            node["softwareUpdate"],
            json!([{"name": "openssl", "version": "3.5.7-1~deb13u2", "kind": "security"}])
        );
        // Without filter, everything
        let node: Value =
            serde_json::from_str(&format_node(only(&body), None, global()).unwrap()).unwrap();
        assert_eq!(node["software"].as_array().unwrap().len(), 4);
    }

    #[test]
    fn empty_software_filter_refused() {
        // It would match every package
        for filter in ["", "  "] {
            assert!(SoftwareFilter::parse(filter).is_err(), "'{filter}'");
        }
    }

    #[test]
    fn too_many_matching_packages() {
        let packages: Vec<Value> = (0..=MAX_PACKAGES)
            .map(|i| json!({"name": format!("lib{i}"), "version": "1"}))
            .collect();
        let body = json!({"data": {"nodes": [{"id": "root", "software": packages}]}}).to_string();
        let error = format_node(
            only(&body),
            Some(&SoftwareFilter::parse("lib").unwrap()),
            global(),
        )
        .unwrap_err()
        .to_string();
        assert_eq!(
            error,
            "101 packages in `software` match 'lib': give a more specific software name"
        );
        let filter = SoftwareFilter::parse("lib1").unwrap();
        assert!(format_node(only(&body), Some(&filter), global()).is_ok());
    }

    #[test]
    fn noise_trimmed() {
        // Shaped as the `api_nodes.yml` webapp test answer
        let body = json!({"data": {"nodes": [{
            "id": "root",
            "machine": {"id": "45f70964-7ec9-ed75-61a5-ed7acbde17f4", "type": "Virtual",
                        "serialNumber": "ece12459"},
            "managementTechnology": [{"name": "Rudder", "version": "Missing version",
                                      "capabilities": [], "nodeKind": "node"},
                                     {"name": "Rudder", "version": "9.1.0",
                                      "capabilities": ["xml", "yaml"]}]
        }]}})
        .to_string();
        let node: Value =
            serde_json::from_str(&format_node(only(&body), None, global()).unwrap()).unwrap();
        assert_eq!(
            node,
            json!({
                "id": "root",
                "machine": {"type": "Virtual", "serialNumber": "ece12459"},
                "managementTechnology": [{"name": "Rudder", "nodeKind": "node"},
                                         {"name": "Rudder", "version": "9.1.0"}]
            })
        );
    }

    #[test]
    fn no_node_or_several() {
        let single = |body| single_node(nodes(body).unwrap(), "web");
        assert_eq!(single(r#"{"data":{"nodes":[]}}"#).unwrap(), None);
        let several = single(r#"{"data":{"nodes":[{"id":"a"},{"id":"b"}]}}"#)
            .unwrap_err()
            .to_string();
        assert!(several.ends_with("use one of their ids: a, b"), "{several}");
        assert_eq!(nodes("{}").unwrap_err().to_string(), UNEXPECTED);
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
