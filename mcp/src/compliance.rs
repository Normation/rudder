//! Compliance from the Rudder API, summarized for the model.
//!
//! Rule and node compliance are trees of the same objects in different orders (rule → directive →
//! component → node → value, or node → rule → directive → component → value), so one walker
//! handles both. It keeps what is not compliant, down to the failing values and their messages,
//! and only counts the compliant parts.

use serde::Deserialize;
use serde_json::{Map, Value};

/// Upper bound on the summary size: a rule applied to many nodes can have a very large tree
const MAX_LINES: usize = 300;

/// Statuses that count as compliant
const HEALTHY: [&str; 5] = [
    "successAlreadyOK",
    "successRepaired",
    "successNotApplicable",
    "auditCompliant",
    "auditNotApplicable",
];

/// Keys holding the children of a compliance object. The first present one is used: a rule has
/// both `directives` and `nodes` (two views of the same reports), the first is enough.
const CHILDREN: [&str; 5] = ["rules", "directives", "components", "nodes", "values"];

/// Global compliance (`GET /compliance`) and rules compliance (`GET /compliance/rules?level=1`)
pub fn global(global_body: &str, rules_body: &str) -> Result<String, String> {
    #[derive(Deserialize)]
    struct Global {
        data: GlobalData,
    }
    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct GlobalData {
        global_compliance: Map<String, Value>,
    }

    let global = serde_json::from_str::<Global>(global_body)
        .map_err(unexpected)?
        .data
        .global_compliance;
    let mut rules = data_array(rules_body, "rules")?;
    // Worst first
    rules.sort_by(|a, b| {
        let rate = |v: &Value| v["compliance"].as_f64().unwrap_or(0.0);
        rate(a).total_cmp(&rate(b))
    });

    let mut lines = vec![format!(
        "Global compliance: {}%{}",
        global.get("compliance").unwrap_or(&Value::Null),
        details(global.get("complianceDetails"))
    )];
    lines.push(format!("Rules ({}):", rules.len()));
    for rule in &rules {
        lines.push(format!("- {}", label(rule)));
    }
    Ok(finish(lines))
}

/// Summary of a node (`GET /compliance/nodes/{id}`) or rule (`GET /compliance/rules/{id}`)
/// compliance tree. `key` is `nodes` or `rules`.
pub fn tree(body: &str, key: &str) -> Result<String, String> {
    let entries = data_array(body, key)?;
    let mut lines = Vec::new();
    for entry in &entries {
        walk(entry, 0, &mut lines);
    }
    if lines.is_empty() {
        return Err(format!(
            "no compliance data in the Rudder API response ({key})"
        ));
    }
    Ok(finish(lines))
}

fn walk(entry: &Value, depth: usize, lines: &mut Vec<String>) {
    let indent = "  ".repeat(depth);
    lines.push(format!("{indent}- {}", label(entry)));
    if depth > 0 && healthy(entry) {
        return;
    }
    let Some((key, children)) = CHILDREN
        .iter()
        .find_map(|key| entry[*key].as_array().map(|c| (*key, c)))
        .filter(|(_, children)| !children.is_empty())
    else {
        // A 0% rate would otherwise read as failing
        if entry["complianceDetails"]
            .as_object()
            .is_none_or(Map::is_empty)
        {
            lines.push(format!(
                "{indent}  (no compliance data: nothing applied, or no reports received yet)"
            ));
        }
        return;
    };
    if key == "values" {
        for value in children {
            for report in value["reports"].as_array().into_iter().flatten() {
                let status = report["status"].as_str().unwrap_or("unknown");
                if !HEALTHY.contains(&status) {
                    let message = report["message"]
                        .as_str()
                        .map(|m| format!(": {m}"))
                        .unwrap_or_default();
                    lines.push(format!(
                        "{indent}  - {}: {status}{message}",
                        value["value"].as_str().unwrap_or("-")
                    ));
                }
            }
        }
        return;
    }
    // Top level: every child on one line (an overview); below, only what is not compliant
    let mut compliant = 0;
    for child in children {
        if depth > 0 && healthy(child) {
            compliant += 1;
        } else {
            walk(child, depth + 1, lines);
        }
    }
    if compliant > 0 {
        lines.push(format!("{indent}  ({compliant} more compliant {key})"));
    }
}

/// `name (id): 83.33% [error 16.67%, successAlreadyOK 83.33%]`, plus why a directive is skipped
fn label(entry: &Value) -> String {
    let name = entry["name"].as_str().unwrap_or("-");
    let id = match entry["id"].as_str() {
        Some(id) if id != name => format!(" ({id})"),
        _ => String::new(),
    };
    let compliance = match &entry["compliance"] {
        Value::Null => String::new(),
        rate => format!(": {rate}%"),
    };
    let skipped = match entry["skippedDetails"]["overridingRuleName"].as_str() {
        Some(rule) => format!(" (skipped, overridden by rule {rule})"),
        None => String::new(),
    };
    format!(
        "{name}{id}{compliance}{}{skipped}",
        details(entry.get("complianceDetails"))
    )
}

/// Compliant when every status in the details is a success. No details means no data to show.
fn healthy(entry: &Value) -> bool {
    entry["complianceDetails"]
        .as_object()
        .is_none_or(|d| d.keys().all(|status| HEALTHY.contains(&status.as_str())))
}

/// `[error 16.67%, successAlreadyOK 83.33%]`, largest first (ties by status name), rates as
/// Rudder gives them
fn details(details: Option<&Value>) -> String {
    let Some(details) = details.and_then(Value::as_object).filter(|d| !d.is_empty()) else {
        return String::new();
    };
    let mut parts: Vec<(&String, &Value)> = details.iter().collect();
    parts.sort_by(|a, b| {
        let rate = |v: &Value| v.as_f64().unwrap_or(0.0);
        rate(b.1).total_cmp(&rate(a.1))
    });
    let parts: Vec<String> = parts
        .iter()
        .map(|(status, rate)| format!("{status} {rate}%"))
        .collect();
    format!(" [{}]", parts.join(", "))
}

fn data_array(body: &str, key: &str) -> Result<Vec<Value>, String> {
    let mut response: Value = serde_json::from_str(body).map_err(unexpected)?;
    match response["data"][key].take() {
        Value::Array(entries) => Ok(entries),
        _ => Err(format!(
            "unexpected Rudder API response: no `data.{key}` list"
        )),
    }
}

fn finish(mut lines: Vec<String>) -> String {
    if lines.len() > MAX_LINES {
        let more = lines.len() - MAX_LINES;
        lines.truncate(MAX_LINES);
        lines.push(format!(
            "... {more} more lines not shown: ask for a specific rule or node"
        ));
    }
    lines.join("\n")
}

fn unexpected(e: serde_json::Error) -> String {
    format!("unexpected Rudder API response: {e}")
}

#[cfg(test)]
mod tests {
    use pretty_assertions::assert_eq;
    use serde_json::json;

    use super::*;

    #[test]
    fn global_worst_rules_first() {
        // Real 9.2 responses shape
        let global_body = r#"{"action":"getGlobalCompliance","result":"success","data":{"globalCompliance":{"compliance":75,"complianceDetails":{"successAlreadyOK":75.0,"error":25.0}}}}"#;
        let rules = json!({"data": {"rules": [
            {"id": "r1", "name": "Global configuration for all nodes", "compliance": 100.0,
             "complianceDetails": {"successAlreadyOK": 100.0}},
            {"id": "r2", "name": "Web servers", "compliance": 50.0,
             "complianceDetails": {"successAlreadyOK": 50.0, "error": 50.0}}
        ]}})
        .to_string();
        assert_eq!(
            global(global_body, &rules).unwrap(),
            "Global compliance: 75% [successAlreadyOK 75.0%, error 25.0%]\n\
             Rules (2):\n\
             - Web servers (r2): 50.0% [error 50.0%, successAlreadyOK 50.0%]\n\
             - Global configuration for all nodes (r1): 100.0% [successAlreadyOK 100.0%]"
        );
    }

    /// Webapp API test data (`api_compliance.yml`, node `bn1`), with a report message added
    fn node_compliance() -> String {
        let directive = |id: &str, rate: f64, status: &str, message: Option<&str>| {
            let mut report = json!({"status": status});
            if let Some(message) = message {
                report["message"] = json!(message);
            }
            json!({"id": id, "name": id, "compliance": rate, "complianceDetails": {status: 100.0},
                   "components": [{"name": format!("{id}-component"), "compliance": rate,
                                   "complianceDetails": {status: 100.0},
                                   "values": [{"value": format!("{id}-value"), "reports": [report]}]}]})
        };
        json!({"action": "getNodeComplianceId", "result": "success", "data": {"nodes": [{
            "id": "bn1", "name": "node1.localhost", "compliance": 66.66,
            "complianceDetails": {"successAlreadyOK": 33.33, "successRepaired": 33.33, "error": 33.34},
            "rules": [
                {"id": "br1", "name": "R1", "compliance": 100.0, "complianceDetails": {"successAlreadyOK": 100.0},
                 "directives": [directive("d1", 100.0, "successAlreadyOK", None)]},
                {"id": "br3", "name": "R3", "compliance": 0.0, "complianceDetails": {"error": 100.0},
                 "directives": [
                     directive("d2", 0.0, "error", Some("Package nginx could not be installed")),
                     directive("d3", 100.0, "successRepaired", None)
                 ]}
            ]
        }]}})
        .to_string()
    }

    #[test]
    fn node_tree_keeps_what_fails() {
        assert_eq!(
            tree(&node_compliance(), "nodes").unwrap(),
            "- node1.localhost (bn1): 66.66% [error 33.34%, successAlreadyOK 33.33%, successRepaired 33.33%]\n\
             \x20 - R1 (br1): 100.0% [successAlreadyOK 100.0%]\n\
             \x20 - R3 (br3): 0.0% [error 100.0%]\n\
             \x20   - d2: 0.0% [error 100.0%]\n\
             \x20     - d2-component: 0.0% [error 100.0%]\n\
             \x20       - d2-value: error: Package nginx could not be installed\n\
             \x20   (1 more compliant directives)"
        );
    }

    #[test]
    fn rule_tree_uses_the_directive_view_and_shows_skipped() {
        let body = json!({"data": {"rules": [{
            "id": "br6", "name": "R6", "complianceDetails": {"successAlreadyOK": 100.0},
            "directives": [
                {"id": "d1", "name": "copy", "complianceDetails": {"successAlreadyOK": 100.0},
                 "components": []},
                {"id": "d4", "name": "d4", "complianceDetails": {},
                 "skippedDetails": {"overridingRuleId": "br5", "overridingRuleName": "R5"},
                 "components": []}
            ],
            "nodes": [{"id": "bn4", "name": "node1.localhost", "complianceDetails": {"successAlreadyOK": 100.0}}]
        }]}})
        .to_string();
        assert_eq!(
            tree(&body, "rules").unwrap(),
            "- R6 (br6) [successAlreadyOK 100.0%]\n\
             \x20 - copy (d1) [successAlreadyOK 100.0%]\n\
             \x20 - d4 (skipped, overridden by rule R5)"
        );
    }

    #[test]
    fn empty_compliance_is_explained() {
        // Real 9.2 response for a root server without user rules
        let body = r#"{"data":{"nodes":[{"id":"root","name":"server.rudder.local","compliance":0.0,"complianceDetails":{},"rules":[]}]}}"#;
        assert_eq!(
            tree(body, "nodes").unwrap(),
            "- server.rudder.local (root): 0.0%\n\
             \x20 (no compliance data: nothing applied, or no reports received yet)"
        );
    }

    #[test]
    fn large_trees_are_cut() {
        let nodes: Vec<Value> = (0..400)
            .map(|i| json!({"id": format!("n{i}"), "name": format!("node{i}"), "complianceDetails": {"error": 100.0}}))
            .collect();
        let body = json!({"data": {"rules": [{"id": "r", "name": "R", "complianceDetails": {"error": 100.0},
            "nodes": nodes}]}})
        .to_string();
        let summary = tree(&body, "rules").unwrap();
        assert_eq!(summary.lines().count(), MAX_LINES + 1);
        assert!(summary.ends_with("... 101 more lines not shown: ask for a specific rule or node"));
    }

    #[test]
    fn unexpected_responses() {
        assert!(
            tree("{}", "nodes")
                .unwrap_err()
                .contains("no `data.nodes` list")
        );
        assert!(
            tree(r#"{"data":{"nodes":[]}}"#, "nodes")
                .unwrap_err()
                .contains("no compliance data")
        );
    }
}
