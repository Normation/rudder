// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

use anyhow::{Context, Result, ensure};
use axum::http::request::Parts;
use rmcp::{
    handler::server::{tool::Extension, wrapper::Parameters},
    schemars, tool, tool_router,
};
use std::collections::HashMap;

use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

use super::{Rudder, UNEXPECTED, caller, data, node::NodeRef, tool_error};
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

#[derive(Deserialize)]
struct Techniques {
    techniques: [TechniqueResponse; 1],
}

/// `GET techniques/{id}/{version}?format=yaml`: an editor technique is only its YAML, a built-in
/// one only its metadata
#[derive(Deserialize)]
#[serde(untagged)]
enum TechniqueResponse {
    Editor { content: String },
    BuiltIn(Technique),
}

/// One shape for every technique: the metadata, plus the YAML definition of editor techniques
#[derive(Deserialize, Serialize)]
struct Technique {
    id: String,
    name: String,
    version: String,
    /// `built-in` or `editor`
    source: String,
    /// The `technique.yml`, editor techniques only
    #[serde(default, skip_serializing_if = "Option::is_none")]
    content: Option<String>,
}

/// The parts of an editor technique's YAML used here, the rest is passed through as is
#[derive(Deserialize)]
struct TechniqueYaml {
    name: String,
    #[serde(default)]
    params: Vec<TechniqueParameter>,
}

#[derive(Deserialize)]
struct TechniqueParameter {
    id: String,
    name: String,
}

impl TechniqueYaml {
    /// The parameter names, by the name of their directive variable
    fn parameter_names(&self) -> HashMap<String, &str> {
        self.params
            .iter()
            .map(|p| (p.id.to_uppercase(), p.name.as_str()))
            .collect()
    }
}

// The REST client splits paths on '/'. Each identifier here must occupy exactly one segment.
fn segment(id: &str) -> Result<&str> {
    ensure!(
        !matches!(id, "" | "." | "..") && !id.contains('/'),
        "{UNEXPECTED}: invalid identifier"
    );
    Ok(id)
}

#[tool_router(router = node_policies_router, vis = "pub(super)")]
impl Rudder {
    #[tool(
        description = "List the rules and directives reported for a node by id or hostname, including overridden directives. Pass a directive id from the list to get its configured parameters (Rudder expressions are preserved) and its versioned technique. The technique always has id, name, version and source (`built-in` or `editor`); editor techniques also have their YAML definition in `content`, and the directive's variables are named after their parameters.",
        annotations(read_only_hint = true)
    )]
    async fn node_policies(
        &self,
        Extension(parts): Extension<Parts>,
        Parameters(query): Parameters<PolicyQuery>,
    ) -> Result<String, String> {
        let api = &caller(&parts)?.api;
        let node = NodeRef::parse(&query.node).id(api).await?;
        policies(api, &node, query.directive.as_deref())
            .await
            .map_err(tool_error)
    }
}

async fn policies(api: &CallerApi, node: &str, directive_id: Option<&str>) -> Result<String> {
    // Some Rudder versions omit directive references at level 3. Level 4 also omits report values.
    let path = format!("compliance/nodes/{}", segment(node)?);
    let body = api.get(&path, &[("level", "4")], "compliance_read").await?;
    let [mut overview] = data::<Nodes>(&body)?.nodes;
    ensure!(
        overview.id.eq_ignore_ascii_case(node),
        "{UNEXPECTED}: node id mismatch"
    );
    let Some(id) = directive_id else {
        return Ok(serde_json::to_string(&overview)?);
    };

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

    let path = format!(
        "techniques/{}/{}",
        segment(&directive.technique_name)?,
        segment(&directive.technique_version)?
    );
    let body = api
        .get(&path, &[("format", "yaml")], "technique_read")
        .await?;
    let [technique] = data::<Techniques>(&body)?.techniques;
    let technique = match technique {
        TechniqueResponse::BuiltIn(technique) => technique,
        TechniqueResponse::Editor { content } => {
            let yaml: TechniqueYaml = serde_yaml::from_str(&content)
                .context(UNEXPECTED)
                .context("could not read the technique YAML")?;
            directive
                .parameters
                .section
                .name_parameters(&yaml.parameter_names());
            Technique {
                // From the directive: in the YAML, an unquoted version (`2.0`) is a number
                id: directive.technique_name.clone(),
                version: directive.technique_version.clone(),
                name: yaml.name,
                source: "editor".to_owned(),
                content: Some(content),
            }
        }
    };
    Ok(json!({"node": overview, "directive": directive, "technique": technique}).to_string())
}
