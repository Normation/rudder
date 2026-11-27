// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

use rmcp::{
    ErrorData,
    handler::server::wrapper::Parameters,
    model::{GetPromptResult, PromptMessage, Role},
    prompt, prompt_router, schemars,
};
use serde::Deserialize;

use crate::tools::Rudder;

/// The technique best practices, read by the model as written for people. Those of the rudderc
/// documentation (#30047) minus the rules the model cannot check by reading (running rudderc, the
/// webapp logs).
const BEST_PRACTICES: &str = include_str!("best-practices.md");

/// What makes a node's policies inconsistent as a whole
const NODE_CONSISTENCY: &str = include_str!("node-consistency.md");

#[derive(Debug, Deserialize, schemars::JsonSchema)]
#[serde(deny_unknown_fields)]
struct AuditArguments {
    /// Node to audit, by id or hostname: its policies as a whole, and its editor techniques.
    node: Option<String>,
    /// Technique to audit, by id in Rudder.
    technique: Option<String>,
    /// Version of the technique, if it has several.
    version: Option<String>,
}

#[prompt_router(router = "audit_policies_router", vis = "pub(super)")]
impl Rudder {
    #[prompt(
        description = "Audit Rudder policies: a node's policies as a whole, for conflicts and inconsistencies, and techniques against Rudder's technique best practices. Give a node, a technique, or neither to review a technique.yml you provide."
    )]
    async fn audit_policies(
        &self,
        Parameters(arguments): Parameters<AuditArguments>,
    ) -> Result<GetPromptResult, ErrorData> {
        let text = audit(&arguments)?;
        Ok(GetPromptResult::new(vec![PromptMessage::new_text(
            Role::User,
            text,
        )]))
    }
}

/// The request to the model: what to read with which tools, how to report, then the checklists
fn audit(arguments: &AuditArguments) -> Result<String, ErrorData> {
    let mut tasks = vec![];
    if let Some(node) = &arguments.node {
        tasks.push(format!(
            "Audit the policies of the node {node:?}, as a whole:\n\
             1. `get_node` with this node: its OS and policy mode.\n\
             2. `get_node_policies` with this node: its rules and directives (skipped ones \
             included), groups and properties.\n\
             3. `get_node_policies` with this node and each directive id: the directive's \
             parameters and technique, with the YAML definition of editor techniques.\n\
             4. `get_node_compliance` with this node.\n\
             Check the directives together against \"Consistency of a node's policies\" below, \
             and each editor technique once against \"Rudder technique Best Practices\". \
             Built-in techniques have no definition to review, only their parameters."
        ));
    }
    match (&arguments.technique, &arguments.version) {
        (Some(technique), version) => {
            let version = version
                .as_ref()
                .map(|v| format!(" and version {v:?}"))
                .unwrap_or_default();
            tasks.push(format!(
                "Audit the technique {technique:?}: read it with `get_technique` (technique \
                 {technique:?}{version}), and review its YAML definition against \"Rudder \
                 technique Best Practices\" below. A built-in technique has no definition to \
                 review: say so."
            ));
        }
        (None, Some(_)) => {
            return Err(ErrorData::invalid_params(
                "`version` needs a `technique`",
                None,
            ));
        }
        (None, None) if tasks.is_empty() => tasks.push(
            "Review the technique I give you (its technique.yml) against \"Rudder technique \
             Best Practices\" below. If I have not given it yet, ask me for it, or for the id of \
             a technique in Rudder to read with `get_technique`."
                .to_owned(),
        ),
        (None, None) => (),
    }

    let mut text = tasks.join("\n\n");
    text.push_str(
        "\n\nGo through every check and rule, and report each one broken, most severe first: \
         conflicts between directives and broken required rules, then recommended ones. For \
         each finding, give where it is (rule, directive, technique, quoting the YAML or \
         parameter concerned), the check or rule broken (its heading), why it matters, and the \
         fix. Say what you could not check and why (e.g. a tool refused for lack of a right). \
         Only report: the tools read, they change nothing.\n\n",
    );
    if arguments.node.is_some() {
        text.push_str(NODE_CONSISTENCY);
        text.push_str("\n\n");
    }
    text.push_str(BEST_PRACTICES);
    Ok(text)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn arguments(node: Option<&str>, technique: Option<&str>) -> AuditArguments {
        AuditArguments {
            node: node.map(str::to_owned),
            technique: technique.map(str::to_owned),
            version: None,
        }
    }

    #[test]
    fn the_node_checklist_only_for_a_node() {
        let node = audit(&arguments(Some("web1"), None)).unwrap();
        assert!(node.starts_with("Audit the policies of the node \"web1\""));
        assert!(node.contains(NODE_CONSISTENCY) && node.ends_with(BEST_PRACTICES));

        let technique = audit(&arguments(None, Some("ntp"))).unwrap();
        assert!(technique.contains("`get_technique` (technique \"ntp\")"));
        assert!(!technique.contains(NODE_CONSISTENCY) && technique.ends_with(BEST_PRACTICES));

        let both = audit(&arguments(Some("web1"), Some("ntp"))).unwrap();
        assert!(both.contains("node \"web1\"") && both.contains("technique \"ntp\""));

        let neither = audit(&arguments(None, None)).unwrap();
        assert!(neither.starts_with("Review the technique I give you"));
    }

    #[test]
    fn a_version_needs_a_technique() {
        let version = AuditArguments {
            version: Some("1.0".to_owned()),
            ..arguments(Some("web1"), None)
        };
        assert!(audit(&version).is_err());
    }
}
