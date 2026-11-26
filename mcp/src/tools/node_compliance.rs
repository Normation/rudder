// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

//! A node's compliance, summarized for the model: the full tree (rule → directive → component →
//! value) is too large to send whole, so it keeps what is not compliant, down to the failing
//! values and their messages, and only counts the compliant parts.

use std::collections::BTreeMap;

use anyhow::{Result, bail};
use axum::http::request::Parts;
use rmcp::{
    handler::server::{tool::Extension, wrapper::Parameters},
    schemars, tool, tool_router,
};
use serde::Deserialize;

use super::{Rudder, UNEXPECTED, caller, data, node::NodeRef, tool_error};

#[derive(Debug, Deserialize, schemars::JsonSchema)]
#[serde(deny_unknown_fields)]
struct NodeComplianceQuery {
    /// Node id (UUID, or `root` for the Rudder server), or hostname exactly as in the inventory
    node: String,
}

#[tool_router(router = node_compliance_router, vis = "pub(super)")]
impl Rudder {
    #[tool(
        description = "A node's compliance, by id or hostname, as plain text: its compliance and that of every rule applied to it; for the rules not fully compliant, the directives, components and values that are not, with the agent's report messages. Compliant parts are only counted. Rates are percentages, with the share of each report status in brackets. Compliant statuses: `successAlreadyOK`, `successRepaired`, `successNotApplicable`, `auditCompliant`, `auditNotApplicable`; any other (e.g. `error`, `auditNonCompliant`, `noReport`, `unexpectedMissingComponent`, `applying`) is not.",
        annotations(read_only_hint = true)
    )]
    async fn node_compliance(
        &self,
        Extension(parts): Extension<Parts>,
        Parameters(NodeComplianceQuery { node }): Parameters<NodeComplianceQuery>,
    ) -> Result<String, String> {
        let api = &caller(&parts)?.api;
        let id = NodeRef::parse(&node).id(api).await?;
        let body = api
            .get(&format!("compliance/nodes/{id}"), &[], "compliance_read")
            .await
            .map_err(tool_error)?;
        let compliance = NodeCompliance::parse(&body).map_err(tool_error)?;
        Ok(compliance.summary())
    }
}

/// Report statuses that count as compliant, by their API name (`statusDisplayName` in the
/// webapp's `Compliance.scala`)
const COMPLIANT: [&str; 5] = [
    "successAlreadyOK",
    "successRepaired",
    "successNotApplicable",
    "auditCompliant",
    "auditNotApplicable",
];

/// Upper bound on the summary, in lines: a node with many failing rules can have a very large tree
const MAX_LINES: usize = 300;

/// The compliance of any level of the tree. Rudder only sends the statuses with a non-zero share.
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Compliance {
    /// Percentage
    compliance: f64,
    /// Percentage of each report status
    compliance_details: BTreeMap<String, f64>,
}

impl Compliance {
    /// Every status is a compliant one. True without any report: then there is nothing to show.
    fn is_compliant(&self) -> bool {
        self.compliance_details
            .keys()
            .all(|status| COMPLIANT.contains(&status.as_str()))
    }

    /// `: 66.66% [error 33.34%, successAlreadyOK 33.33%]`, statuses largest first (ties by name).
    /// Empty without any report: a 0% rate would read as failing.
    fn label(&self) -> String {
        if self.compliance_details.is_empty() {
            return String::new();
        }
        let mut details: Vec<(&String, &f64)> = self.compliance_details.iter().collect();
        // Stable: ties keep the map's order, by name
        details.sort_by(|a, b| b.1.total_cmp(a.1));
        let details: Vec<String> = details
            .iter()
            .map(|(status, rate)| format!("{status} {}", percent(**rate)))
            .collect();
        format!(": {} [{}]", percent(self.compliance), details.join(", "))
    }
}

/// `66.65%`: at most two decimals, without trailing zeros. Rudder's rates are rounded to two
/// decimals, but sent as floats summed on its side, e.g. `66.64999999999999`.
fn percent(rate: f64) -> String {
    let rate = format!("{rate:.2}");
    let rate = rate.trim_end_matches('0').trim_end_matches('.');
    format!("{rate}%")
}

/// `GET /compliance/nodes/{id}`, at the default (full) level
#[derive(Debug, Deserialize)]
struct NodeCompliance {
    id: String,
    name: String,
    #[serde(flatten)]
    compliance: Compliance,
    rules: Vec<RuleCompliance>,
}

#[derive(Debug, Deserialize)]
struct RuleCompliance {
    id: String,
    name: String,
    #[serde(flatten)]
    compliance: Compliance,
    directives: Vec<DirectiveCompliance>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct DirectiveCompliance {
    id: String,
    name: String,
    #[serde(flatten)]
    compliance: Compliance,
    /// Set when another rule's directive of the same unique technique applies instead
    skipped_details: Option<SkippedDetails>,
    components: Vec<Component>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct SkippedDetails {
    overriding_rule_name: String,
}

/// A technique block, holding other components, or a component with values
#[derive(Debug, Deserialize)]
#[serde(untagged)]
enum Component {
    Block {
        name: String,
        #[serde(flatten)]
        compliance: Compliance,
        components: Vec<Component>,
    },
    Values {
        name: String,
        #[serde(flatten)]
        compliance: Compliance,
        values: Vec<ComponentValue>,
    },
}

#[derive(Debug, Deserialize)]
struct ComponentValue {
    value: String,
    reports: Vec<Report>,
}

#[derive(Debug, Deserialize)]
struct Report {
    status: String,
    message: Option<String>,
}

impl NodeCompliance {
    fn parse(body: &str) -> Result<Self> {
        #[derive(Deserialize)]
        struct Nodes {
            nodes: Vec<NodeCompliance>,
        }
        let mut nodes = data::<Nodes>(body)?.nodes;
        if nodes.len() != 1 {
            bail!("{UNEXPECTED}: {} nodes instead of one", nodes.len());
        }
        Ok(nodes.remove(0))
    }

    /// Every rule, for an overview; below them, only what is not compliant
    fn summary(&self) -> String {
        let mut summary = Summary::default();
        summary.line(0, entry(&self.name, Some(&self.id), &self.compliance));
        if self.compliance.compliance_details.is_empty() {
            summary.note(
                1,
                "(no compliance data: nothing applied, or no reports received yet)",
            );
        }
        for rule in &self.rules {
            summary.line(1, entry(&rule.name, Some(&rule.id), &rule.compliance));
            if !rule.compliance.is_compliant() {
                summary.failing(
                    &rule.directives,
                    2,
                    "directives",
                    // A skipped directive has no reports: shown for why
                    |d| d.compliance.is_compliant() && d.skipped_details.is_none(),
                    Self::directive,
                );
            }
        }
        summary.finish()
    }

    fn directive(summary: &mut Summary, depth: usize, directive: &DirectiveCompliance) {
        let skipped = match &directive.skipped_details {
            Some(skipped) => format!(
                " (skipped, overridden by rule {})",
                skipped.overriding_rule_name
            ),
            None => String::new(),
        };
        let label = entry(&directive.name, Some(&directive.id), &directive.compliance);
        summary.line(depth, format!("{label}{skipped}"));
        if !directive.compliance.is_compliant() {
            summary.failing(
                &directive.components,
                depth + 1,
                "components",
                |c| c.compliance().is_compliant(),
                Self::component,
            );
        }
    }

    fn component(summary: &mut Summary, depth: usize, component: &Component) {
        match component {
            Component::Block {
                name,
                compliance,
                components,
            } => {
                summary.line(depth, entry(name, None, compliance));
                summary.failing(
                    components,
                    depth + 1,
                    "components",
                    |c| c.compliance().is_compliant(),
                    Self::component,
                );
            }
            Component::Values {
                name,
                compliance,
                values,
            } => {
                summary.line(depth, entry(name, None, compliance));
                for value in values {
                    for report in &value.reports {
                        if !COMPLIANT.contains(&report.status.as_str()) {
                            let message = match &report.message {
                                Some(message) => format!(": {message}"),
                                None => String::new(),
                            };
                            summary.line(
                                depth + 1,
                                format!("{}: {}{message}", value.value, report.status),
                            );
                        }
                    }
                }
            }
        }
    }
}

impl Component {
    fn compliance(&self) -> &Compliance {
        match self {
            Self::Block { compliance, .. } | Self::Values { compliance, .. } => compliance,
        }
    }
}

/// `name (id): rate [details]`, the id left out when it is the name
fn entry(name: &str, id: Option<&str>, compliance: &Compliance) -> String {
    let id = match id {
        Some(id) if id != name => format!(" ({id})"),
        _ => String::new(),
    };
    format!("{name}{id}{}", compliance.label())
}

/// The summary being written: an indented list
#[derive(Debug, Default)]
struct Summary(Vec<String>);

impl Summary {
    fn line(&mut self, depth: usize, text: String) {
        self.0.push(format!("{}- {text}", "  ".repeat(depth)));
    }

    /// A line about the entries at `depth`, not an entry
    fn note(&mut self, depth: usize, text: &str) {
        self.0.push(format!("{}{text}", "  ".repeat(depth)));
    }

    /// Writes the `entries` that are not compliant with `write`, and only counts the others
    fn failing<T>(
        &mut self,
        entries: &[T],
        depth: usize,
        kind: &str,
        is_compliant: impl Fn(&T) -> bool,
        write: impl Fn(&mut Self, usize, &T),
    ) {
        let mut compliant = 0;
        for entry in entries {
            if is_compliant(entry) {
                compliant += 1;
            } else {
                write(self, depth, entry);
            }
        }
        if compliant > 0 {
            self.note(depth, &format!("({compliant} more compliant {kind})"));
        }
    }

    fn finish(mut self) -> String {
        if self.0.len() > MAX_LINES {
            let more = self.0.len() - MAX_LINES;
            self.0.truncate(MAX_LINES);
            self.0.push(format!("... {more} more lines not shown"));
        }
        self.0.join("\n")
    }
}

#[cfg(test)]
mod tests {
    use pretty_assertions::assert_eq;
    use serde_json::{Value, json};

    use super::*;

    #[test]
    fn percent_has_at_most_two_decimals() {
        assert_eq!(percent(66.64999999999999), "66.65%");
        assert_eq!(percent(33.333333333333336), "33.33%");
        assert_eq!(percent(12.5), "12.5%");
        assert_eq!(percent(100.0), "100%");
        assert_eq!(percent(0.0), "0%");
        assert_eq!(percent(0.004), "0%");
    }

    /// The webapp API test answer (`api_compliance.yml`, node `bn1`), with a report message, a
    /// block and a skipped directive added
    fn bn1() -> Value {
        let directive = |id: &str, rate: f64, status: &str, message: Option<&str>| {
            let mut report = json!({"status": status});
            if let Some(message) = message {
                report["message"] = json!(message);
            }
            json!({"id": id, "name": id, "compliance": rate, "policyMode": "audit",
                   "complianceDetails": {status: 100.0},
                   "components": [{"name": format!("{id}-component"), "compliance": rate,
                                   "complianceDetails": {status: 100.0},
                                   "values": [{"value": format!("{id}-value"), "reports": [report]}]}]})
        };
        // A technique with a block
        let web = json!({"id": "d4", "name": "Web", "compliance": 50.0, "policyMode": "audit",
        "complianceDetails": {"auditNonCompliant": 50.0, "auditCompliant": 50.0},
        "components": [{"name": "Configure", "compliance": 50.0,
            "complianceDetails": {"auditNonCompliant": 50.0, "auditCompliant": 50.0},
            "components": [
                {"name": "file", "compliance": 0.0, "complianceDetails": {"auditNonCompliant": 100.0},
                 "values": [{"value": "/etc/motd", "reports": [
                     {"status": "auditNonCompliant", "message": "content differs"}]}]},
                {"name": "service", "compliance": 100.0, "complianceDetails": {"auditCompliant": 100.0},
                 "values": [{"value": "nginx", "reports": [{"status": "auditCompliant"}]}]}
            ]}]});
        json!({"action": "getNodeComplianceId", "result": "success", "data": {"nodes": [{
            "id": "bn1", "name": "node1.localhost", "compliance": 66.66, "mode": "full-compliance",
            "policyMode": "audit",
            "complianceDetails": {"successAlreadyOK": 33.33, "error": 33.34, "successRepaired": 33.33},
            "rules": [
                {"id": "br1", "name": "R1", "compliance": 100.0, "policyMode": "audit",
                 "complianceDetails": {"successAlreadyOK": 100.0},
                 "directives": [directive("d1", 100.0, "successAlreadyOK", None)]},
                {"id": "br3", "name": "R3", "compliance": 0.0, "policyMode": "audit",
                 "complianceDetails": {"error": 100.0},
                 "directives": [
                     directive("d2", 0.0, "error", Some("Package nginx could not be installed")),
                     directive("d3", 100.0, "successRepaired", None),
                     web,
                     // No reports: it does not apply
                     {"id": "d5", "name": "d5", "compliance": 0.0, "policyMode": "audit",
                      "complianceDetails": {},
                      "skippedDetails": {"overridingRuleId": "br5", "overridingRuleName": "R5"},
                      "components": []}
                 ]}
            ]
        }]}})
    }

    fn summary(body: &Value) -> String {
        NodeCompliance::parse(&body.to_string()).unwrap().summary()
    }

    #[test]
    fn keeps_what_is_not_compliant() {
        assert_eq!(
            summary(&bn1()),
            "- node1.localhost (bn1): 66.66% [error 33.34%, successAlreadyOK 33.33%, successRepaired 33.33%]\n\
             \x20 - R1 (br1): 100% [successAlreadyOK 100%]\n\
             \x20 - R3 (br3): 0% [error 100%]\n\
             \x20   - d2: 0% [error 100%]\n\
             \x20     - d2-component: 0% [error 100%]\n\
             \x20       - d2-value: error: Package nginx could not be installed\n\
             \x20   - Web (d4): 50% [auditCompliant 50%, auditNonCompliant 50%]\n\
             \x20     - Configure: 50% [auditCompliant 50%, auditNonCompliant 50%]\n\
             \x20       - file: 0% [auditNonCompliant 100%]\n\
             \x20         - /etc/motd: auditNonCompliant: content differs\n\
             \x20       (1 more compliant components)\n\
             \x20   - d5 (skipped, overridden by rule R5)\n\
             \x20   (1 more compliant directives)"
        );
    }

    #[test]
    fn empty_compliance_is_explained() {
        // Real 9.2 answer for a Rudder server without user rules
        let body = json!({"data": {"nodes": [{"id": "root", "name": "server.rudder.local",
            "compliance": 0.0, "complianceDetails": {}, "rules": []}]}});
        assert_eq!(
            summary(&body),
            "- server.rudder.local (root)\n\
             \x20 (no compliance data: nothing applied, or no reports received yet)"
        );
    }

    #[test]
    fn large_summaries_are_cut() {
        let rules: Vec<Value> = (0..400)
            .map(|i| {
                json!({"id": format!("r{i}"), "name": format!("R{i}"), "compliance": 100.0,
                            "complianceDetails": {"successAlreadyOK": 100.0}, "directives": []})
            })
            .collect();
        let body = json!({"data": {"nodes": [{"id": "root", "name": "server", "compliance": 100.0,
            "complianceDetails": {"successAlreadyOK": 100.0}, "rules": rules}]}});
        let summary = summary(&body);
        assert_eq!(summary.lines().count(), MAX_LINES + 1);
        assert!(
            summary.ends_with("\n... 101 more lines not shown"),
            "{summary}"
        );
    }

    #[test]
    fn unexpected_answers() {
        let error = |body: &str| format!("{:#}", NodeCompliance::parse(body).unwrap_err());
        assert!(error("{}").starts_with(UNEXPECTED));
        assert_eq!(
            error(r#"{"data":{"nodes":[]}}"#),
            format!("{UNEXPECTED}: 0 nodes instead of one")
        );
    }
}
