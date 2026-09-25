//! Rule definition from the Rudder API: status, directives and targets, with their names.

use axum::http::{Method, request::Parts};
use futures::future::join_all;
use serde::Deserialize;
use serde_json::Value;

use crate::{RudderApi, is_object_id};

/// `GET /rules/{id}` rule, only the fields used here
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Rule {
    id: String,
    display_name: String,
    #[serde(default)]
    short_description: String,
    #[serde(default)]
    long_description: String,
    directives: Vec<String>,
    targets: Vec<Value>,
    enabled: bool,
    system: bool,
    policy_mode: Option<String>,
    status: Option<Status>,
}

#[derive(Debug, Deserialize)]
struct Status {
    value: String,
    details: Option<String>,
}

/// Targets, split between included and excluded ones. A target is `group:<id>` or `special:<kind>`,
/// alone or in `{"include": {"or": [...]}, "exclude": {"or": [...]}}` composites.
#[derive(Debug, Default, PartialEq, Eq)]
struct Targets {
    include: Vec<String>,
    exclude: Vec<String>,
}

impl Targets {
    fn parse(targets: &[Value]) -> Self {
        let mut parsed = Self::default();
        for target in targets {
            match target {
                Value::String(target) => parsed.include.push(target.clone()),
                Value::Object(composite) => {
                    for (side, list) in [
                        ("include", &mut parsed.include),
                        ("exclude", &mut parsed.exclude),
                    ] {
                        // `or` or `and` combinations, both listed as they are
                        for targets in composite
                            .get(side)
                            .and_then(Value::as_object)
                            .into_iter()
                            .flat_map(|combination| combination.values())
                        {
                            list.extend(
                                targets
                                    .as_array()
                                    .into_iter()
                                    .flatten()
                                    .filter_map(|t| t.as_str().map(str::to_owned)),
                            );
                        }
                    }
                }
                _ => {}
            }
        }
        parsed
    }
}

/// Meaning of the built-in `special:` targets
fn special_target(kind: &str) -> Option<&'static str> {
    match kind {
        "all" => Some("all nodes, policy servers included"),
        "all_exceptPolicyServers" => Some("all nodes except policy servers"),
        "all_policyServers" => Some("all policy servers"),
        "all_nodes_without_role" => Some("all nodes without a server role"),
        "all_servers_with_role" => Some("all servers with a role"),
        _ => None,
    }
}

/// Rule summary, with directive and group names fetched concurrently (in this task, so their
/// logs keep the request's account span). An object that cannot be fetched (e.g. a 403 for an ACL
/// token) is shown with the reason, the rest of the summary still comes.
pub async fn info(api: &RudderApi, parts: &Parts, rule_id: &str) -> Result<String, String> {
    let body = api
        .call(parts, Method::GET, &format!("rules/{rule_id}"), &[])
        .await?;
    let rule: Rule = first(&body, "rules")?;
    let targets = Targets::parse(&rule.targets);

    let directives = join_all(
        rule.directives
            .iter()
            .map(|id| fetch(api, parts, "directives", id)),
    );
    let (directives, include, exclude) = tokio::join!(
        directives,
        describe_targets(api, parts, &targets.include),
        describe_targets(api, parts, &targets.exclude)
    );

    let yes_no = |b: bool| if b { "yes" } else { "no" };
    let mut lines = vec![format!("Rule: {} ({})", rule.display_name, rule.id)];
    if let Some(status) = &rule.status {
        let details = status
            .details
            .as_deref()
            .map(|d| format!(": {d}"))
            .unwrap_or_default();
        lines.push(format!("Status: {}{details}", status.value));
    }
    lines.push(format!(
        "Enabled: {}, system: {}, policy mode: {}",
        yes_no(rule.enabled),
        yes_no(rule.system),
        rule.policy_mode.as_deref().unwrap_or("default")
    ));
    for description in [&rule.short_description, &rule.long_description] {
        if !description.is_empty() {
            lines.push(format!("Description: {description}"));
        }
    }
    for (title, items) in [
        ("Directives", directives),
        ("Targets included", include),
        ("Targets excluded", exclude),
    ] {
        lines.push(format!("{title} ({}):", items.len()));
        lines.extend(items.into_iter().map(|item| format!("- {item}")));
    }
    Ok(lines.join("\n"))
}

/// One line per target: group details, or the meaning of a built-in target
async fn describe_targets(api: &RudderApi, parts: &Parts, targets: &[String]) -> Vec<String> {
    join_all(targets.iter().map(|target| async move {
        match target.split_once(':') {
            Some(("group", id)) => fetch(api, parts, "groups", id).await,
            Some(("special", kind)) => format!(
                "{} ({target})",
                special_target(kind).unwrap_or("special target")
            ),
            _ => target.clone(),
        }
    }))
    .await
}

/// One line about a directive or group, or why it is not available
async fn fetch(api: &RudderApi, parts: &Parts, kind: &str, id: &str) -> String {
    if !is_object_id(id) {
        return format!("{id}: details unavailable, not a valid id");
    }
    let body = match api
        .call(parts, Method::GET, &format!("{kind}/{id}"), &[])
        .await
    {
        Ok(body) => body,
        Err(e) => return format!("{id}: details unavailable: {e}"),
    };
    match first::<Value>(&body, kind) {
        Ok(object) if kind == "directives" => describe_directive(&object),
        Ok(object) => describe_group(&object),
        Err(e) => format!("{id}: details unavailable: {e}"),
    }
}

fn describe_directive(directive: &Value) -> String {
    let mut attributes = vec![format!(
        "technique {} {}",
        directive["techniqueName"].as_str().unwrap_or("?"),
        directive["techniqueVersion"].as_str().unwrap_or("?")
    )];
    attributes.push(
        if directive["enabled"].as_bool() == Some(false) {
            "disabled"
        } else {
            "enabled"
        }
        .to_owned(),
    );
    attributes.push(format!(
        "policy mode {}",
        directive["policyMode"].as_str().unwrap_or("default")
    ));
    if directive["system"].as_bool() == Some(true) {
        attributes.push("system".to_owned());
    }
    format!(
        "{} ({}): {}",
        directive["displayName"].as_str().unwrap_or("-"),
        directive["id"].as_str().unwrap_or("-"),
        attributes.join(", ")
    )
}

fn describe_group(group: &Value) -> String {
    let nodes = group["nodeIds"].as_array().map_or(0, Vec::len);
    let mut attributes = vec![format!("{nodes} node{}", if nodes == 1 { "" } else { "s" })];
    attributes.push(
        if group["dynamic"].as_bool() == Some(true) {
            "dynamic"
        } else {
            "static"
        }
        .to_owned(),
    );
    if group["enabled"].as_bool() == Some(false) {
        attributes.push("disabled".to_owned());
    }
    if group["system"].as_bool() == Some(true) {
        attributes.push("system".to_owned());
    }
    format!(
        "group {} ({}): {}",
        group["displayName"].as_str().unwrap_or("-"),
        group["id"].as_str().unwrap_or("-"),
        attributes.join(", ")
    )
}

/// First object of the `data.<key>` list
fn first<T: serde::de::DeserializeOwned>(body: &str, key: &str) -> Result<T, String> {
    let mut response: Value =
        serde_json::from_str(body).map_err(|e| format!("unexpected Rudder API response: {e}"))?;
    let object = response["data"][key]
        .as_array_mut()
        .and_then(|list| (!list.is_empty()).then(|| list.swap_remove(0)))
        .ok_or_else(|| format!("unexpected Rudder API response: no `data.{key}` entry"))?;
    serde_json::from_value(object).map_err(|e| format!("unexpected Rudder API response: {e}"))
}

#[cfg(test)]
mod tests {
    use pretty_assertions::assert_eq;
    use serde_json::json;

    use super::*;

    #[test]
    fn object_ids() {
        assert!(is_object_id("32377fd7-02fd-43d0-aab7-28460a91347b"));
        assert!(is_object_id("hasPolicyServer-root"));
        assert!(is_object_id("common-hasPolicyServer-root"));
        assert!(!is_object_id(""));
        assert!(!is_object_id("../nodes"));
        assert!(!is_object_id("rule?level=1"));
    }

    #[test]
    fn targets_plain_and_composite() {
        // Both shapes, from a real 9.2 server
        assert_eq!(
            Targets::parse(&[json!("group:hasPolicyServer-root")]),
            Targets {
                include: vec!["group:hasPolicyServer-root".to_owned()],
                exclude: vec![],
            }
        );
        assert_eq!(
            Targets::parse(&[json!({
                "include": {"or": ["special:all", "group:web"]},
                "exclude": {"or": ["group:dmz"]}
            })]),
            Targets {
                include: vec!["special:all".to_owned(), "group:web".to_owned()],
                exclude: vec!["group:dmz".to_owned()],
            }
        );
    }

    #[test]
    fn directive_and_group_lines() {
        // Real 9.2 objects
        let directive = json!({"id": "common-hasPolicyServer-root", "displayName": "Common",
            "techniqueName": "common", "techniqueVersion": "1.0", "enabled": true,
            "system": true, "policyMode": "default"});
        assert_eq!(
            describe_directive(&directive),
            "Common (common-hasPolicyServer-root): technique common 1.0, enabled, policy mode default, system"
        );
        let group = json!({"id": "hasPolicyServer-root",
            "displayName": "All Linux Nodes managed by root policy server",
            "nodeIds": ["root"], "dynamic": true, "enabled": true, "system": true});
        assert_eq!(
            describe_group(&group),
            "group All Linux Nodes managed by root policy server (hasPolicyServer-root): 1 node, dynamic, system"
        );
    }

    #[test]
    fn unexpected_responses() {
        assert!(
            first::<Value>(r#"{"data":{"rules":[]}}"#, "rules")
                .unwrap_err()
                .contains("no `data.rules` entry")
        );
    }
}
