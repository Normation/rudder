// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

use anyhow::{Result, ensure};
use axum::http::request::Parts;
use rmcp::{
    handler::server::{tool::Extension, wrapper::Parameters},
    schemars, tool, tool_router,
};
use std::collections::HashMap;

use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

use super::{
    Rudder, UNEXPECTED, caller, data, node::NodeRef, segment, technique::Technique, tool_error,
};
use crate::api::CallerApi;

#[derive(Debug, Deserialize, schemars::JsonSchema)]
#[serde(deny_unknown_fields)]
struct PolicyQuery {
    /// Node id (UUID or root), or exact inventory hostname.
    node: String,
    /// Directive id from this node's overview. Omit to list its rules and directives.
    directive: Option<String>,
}

#[derive(Deserialize)]
struct Nodes {
    nodes: [NodePolicies; 1],
}

#[derive(Deserialize, Serialize)]
struct NodePolicies {
    id: String,
    name: String,
    rules: Vec<Rule>,
}

/// The overview: the node's rules and directives, the groups it belongs to and its properties
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Overview {
    #[serde(flatten)]
    node: NodePolicies,
    groups: Vec<Group>,
    properties: Vec<Property>,
    /// Why Rudder could not resolve the node's properties as a whole
    #[serde(skip_serializing_if = "Option::is_none")]
    properties_error: Option<String>,
}

#[derive(Deserialize)]
struct Groups {
    groups: Vec<GroupResponse>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct GroupResponse {
    id: String,
    display_name: String,
    node_ids: Vec<String>,
}

#[derive(Serialize)]
struct Group {
    id: String,
    name: String,
}

/// `GET nodes/{id}/inheritedProperties`, without data container: a list of one node
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct InheritedProperties {
    node_id: String,
    properties: Vec<PropertyResponse>,
    error_message: Option<String>,
}

/// One of the node's properties, resolved: its value merges those set along its hierarchy
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct PropertyResponse {
    name: String,
    value: Value,
    description: Option<String>,
    provider: Option<String>,
    hierarchy_status: HierarchyStatus,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct HierarchyStatus {
    full_hierarchy: Level,
    error_message: Option<String>,
}

/// Where a property is set, then the next one up (node, groups, global)
#[derive(Deserialize)]
struct Level {
    #[serde(flatten)]
    source: Source,
    parent: Option<Box<Level>>,
}

#[derive(Deserialize, Serialize)]
#[serde(tag = "kind", rename_all = "lowercase")]
enum Source {
    Global,
    Group { id: String, name: String },
    Node { id: String, name: String },
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Property {
    name: String,
    value: Value,
    #[serde(skip_serializing_if = "Option::is_none")]
    description: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    provider: Option<String>,
    /// Where the property is set, closest first: the value merges them, the closest wins
    defined_in: Vec<Source>,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<String>,
}

impl From<PropertyResponse> for Property {
    fn from(p: PropertyResponse) -> Self {
        let mut defined_in = vec![];
        let mut level = Some(Box::new(p.hierarchy_status.full_hierarchy));
        while let Some(Level { source, parent }) = level.map(|l| *l) {
            defined_in.push(source);
            level = parent;
        }
        Property {
            name: p.name,
            value: p.value,
            description: p.description,
            provider: p.provider,
            defined_in,
            error: p.hierarchy_status.error_message,
        }
    }
}

#[derive(Deserialize, Serialize)]
struct Rule {
    id: String,
    name: String,
    directives: Vec<DirectiveRef>,
}

#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct DirectiveRef {
    id: String,
    name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    skipped_details: Option<Value>,
}

#[derive(Deserialize)]
struct Directives {
    directives: [Directive; 1],
}

#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct Directive {
    id: String,
    display_name: String,
    technique_name: String,
    technique_version: String,
    parameters: DirectiveParameters,
}

/// The directive's variables, as Rudder's section tree: `{"section": {"name": "sections", ...}}`
#[derive(Deserialize, Serialize)]
struct DirectiveParameters {
    section: Section,
}

/// Rudder leaves out empty `vars` and `sections`
#[derive(Deserialize, Serialize)]
struct Section {
    name: String,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    vars: Vec<VarEntry>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    sections: Vec<SectionEntry>,
}

#[derive(Deserialize, Serialize)]
struct VarEntry {
    var: Var,
}

#[derive(Deserialize, Serialize)]
struct Var {
    name: String,
    value: String,
}

#[derive(Deserialize, Serialize)]
struct SectionEntry {
    section: Section,
}

impl Section {
    /// Renames the variables named after a technique parameter id to the parameter's name. rudderc
    /// names them by the uppercased id, usually a UUID (`2628FA31-...` for `2628fa31-...`), which
    /// says nothing of what the value is.
    fn name_parameters(&mut self, names: &HashMap<String, &str>) {
        for VarEntry { var } in &mut self.vars {
            if let Some(name) = names.get(&var.name) {
                var.name = (*name).to_owned();
            }
        }
        for SectionEntry { section } in &mut self.sections {
            section.name_parameters(names);
        }
    }
}

#[tool_router(router = get_node_policies_router, vis = "pub(super)")]
impl Rudder {
    #[tool(
        description = "List the rules and directives reported for a node by id or hostname, including overridden directives, with the groups the node belongs to and its properties. Each property has its resolved value (inherited ones included) and `definedIn`: where it is set (node, group or global), closest first, the closest winning when merged. Pass a directive id from the list to get its configured parameters (Rudder expressions are preserved) and its versioned technique. The technique always has id, name, version and source (`built-in` or `editor`); editor techniques also have their YAML definition in `content`, and the directive's variables are named after their parameters.",
        annotations(read_only_hint = true)
    )]
    async fn get_node_policies(
        &self,
        Extension(parts): Extension<Parts>,
        Parameters(query): Parameters<PolicyQuery>,
    ) -> Result<String, String> {
        let api = &caller(&parts)?.api;
        let node = NodeRef::parse(&query.node).id(api).await?;
        match query.directive.as_deref() {
            None => overview(api, &node).await,
            Some(id) => directive(api, &node, id).await.map_err(tool_error),
        }
    }
}

async fn overview(api: &CallerApi, node: &str) -> Result<String, String> {
    let calls = tokio::join!(rules(api, node), groups(api, node), properties(api, node));
    // Every failure, not only the first: the calls need different rights
    let (node, groups, (properties, properties_error)) = match calls {
        (Ok(node), Ok(groups), Ok(properties)) => (node, groups, properties),
        (node, groups, properties) => {
            let errors: Vec<_> = [node.err(), groups.err(), properties.err()]
                .into_iter()
                .flatten()
                .map(tool_error)
                .collect();
            return Err(errors.join("\n"));
        }
    };
    let overview = Overview {
        node,
        groups,
        properties,
        properties_error,
    };
    serde_json::to_string(&overview).map_err(|e| tool_error(e.into()))
}

/// The rules and directives of the node's compliance
async fn rules(api: &CallerApi, node: &str) -> Result<NodePolicies> {
    // Some Rudder versions omit directive references at level 3. Level 4 also omits report values.
    let path = format!("compliance/nodes/{}", segment(node)?);
    let body = api.get(&path, &[("level", "4")], "compliance_read").await?;
    let [overview] = data::<Nodes>(&body)?.nodes;
    ensure!(
        overview.id.eq_ignore_ascii_case(node),
        "{UNEXPECTED}: node id mismatch"
    );
    Ok(overview)
}

/// The groups the node belongs to, by name. Rudder has no endpoint for them: every group is read
/// with its nodes.
async fn groups(api: &CallerApi, node: &str) -> Result<Vec<Group>> {
    let body = api.get("groups", &[], "group_read").await?;
    let mut groups: Vec<_> = data::<Groups>(&body)?
        .groups
        .into_iter()
        .filter(|g| g.node_ids.iter().any(|id| id.eq_ignore_ascii_case(node)))
        .map(|g| Group {
            id: g.id,
            name: g.display_name,
        })
        .collect();
    groups.sort_by(|a, b| a.name.cmp(&b.name));
    Ok(groups)
}

/// The node's properties, inherited ones included, and Rudder's error if it could not resolve them
async fn properties(api: &CallerApi, node: &str) -> Result<(Vec<Property>, Option<String>)> {
    let path = format!("nodes/{}/inheritedProperties", segment(node)?);
    let body = api.get(&path, &[], "node_read").await?;
    let [properties] = data::<[InheritedProperties; 1]>(&body)?;
    ensure!(
        properties.node_id.eq_ignore_ascii_case(node),
        "{UNEXPECTED}: node id mismatch"
    );
    let resolved = properties.properties.into_iter().map(Property::from);
    Ok((resolved.collect(), properties.error_message))
}

/// The node's rules applying the directive, the directive and its technique
async fn directive(api: &CallerApi, node: &str, id: &str) -> Result<String> {
    let mut overview = rules(api, node).await?;
    for rule in &mut overview.rules {
        rule.directives.retain(|d| d.id == id);
    }
    overview.rules.retain(|rule| !rule.directives.is_empty());
    ensure!(
        !overview.rules.is_empty(),
        "Directive '{id}' is not listed for node '{node}'"
    );

    let path = format!("directives/{}", segment(id)?);
    let body = api.get(&path, &[], "directive_read").await?;
    let [mut directive] = data::<Directives>(&body)?.directives;
    ensure!(directive.id == id, "{UNEXPECTED}: directive id mismatch");

    let technique =
        Technique::get(api, &directive.technique_name, &directive.technique_version).await?;
    directive
        .parameters
        .section
        .name_parameters(&technique.parameter_names());
    Ok(json!({"node": overview, "directive": directive, "technique": technique}).to_string())
}
