// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

//! Policy modes: the global one, from the Rudder settings, and the mode that applies to a node
//! given its own. For every tool showing a node's policy mode.

use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use serde_json::Value;

use super::UNEXPECTED;
use crate::api::CallerApi;

/// A policy mode, as Rudder names it
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub(super) enum PolicyMode {
    Audit,
    Enforce,
}

impl PolicyMode {
    /// An object's own policy mode as Rudder sends it, `None` for `default` (none of its own)
    pub(super) fn parse_own(mode: &Value) -> Result<Option<Self>> {
        match mode {
            Value::String(mode) if mode == "default" => Ok(None),
            mode => Ok(Some(Self::deserialize(mode).context(UNEXPECTED)?)),
        }
    }
}

/// The global policy mode, and whether nodes may set their own
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(super) struct GlobalPolicyMode {
    pub(super) mode: PolicyMode,
    pub(super) overridable: bool,
}

impl GlobalPolicyMode {
    /// Reads it from the Rudder settings: two calls, made concurrently. `node_read` is enough for
    /// these two settings, unlike the others.
    pub(super) async fn fetch(api: &CallerApi) -> Result<Self> {
        let (mode, overridable) = tokio::join!(
            api.get("settings/global_policy_mode", &[], "node_read"),
            api.get("settings/global_policy_mode_overridable", &[], "node_read"),
        );
        // Both need the same right: the first failure says it all
        Self::parse(&mode?, &overridable?)
    }

    /// From the bodies of `GET /settings/global_policy_mode` and
    /// `GET /settings/global_policy_mode_overridable`
    fn parse(mode_body: &str, overridable_body: &str) -> Result<Self> {
        #[derive(Deserialize)]
        struct Response<T> {
            data: Data<T>,
        }
        #[derive(Deserialize)]
        struct Data<T> {
            settings: T,
        }
        #[derive(Deserialize)]
        struct Mode {
            global_policy_mode: PolicyMode,
        }
        #[derive(Deserialize)]
        struct Overridable {
            global_policy_mode_overridable: bool,
        }
        let mode = serde_json::from_str::<Response<Mode>>(mode_body)
            .context(UNEXPECTED)?
            .data
            .settings
            .global_policy_mode;
        let overridable = serde_json::from_str::<Response<Overridable>>(overridable_body)
            .context(UNEXPECTED)?
            .data
            .settings
            .global_policy_mode_overridable;
        Ok(Self { mode, overridable })
    }

    /// The mode that applies to a node with its own mode `node` (`None` for Rudder's `default`)
    pub(super) fn apply_to(self, node: Option<PolicyMode>) -> (PolicyMode, PolicyModeSource) {
        match node {
            _ if !self.overridable => (self.mode, PolicyModeSource::Forced),
            Some(mode) => (mode, PolicyModeSource::Node),
            None => (self.mode, PolicyModeSource::Global),
        }
    }
}

/// Where the policy mode that applies to a node comes from
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub(super) enum PolicyModeSource {
    /// The node's own setting
    Node,
    /// The global mode, the node having none of its own
    Global,
    /// The global mode, which nodes cannot override: the node's setting is ignored
    Forced,
}

#[cfg(test)]
mod tests {
    use pretty_assertions::assert_eq;
    use serde_json::json;

    use super::*;

    #[test]
    fn global_policy_mode_from_settings() {
        // Shaped as the `api_settings.yml` webapp test answers
        let mode = r#"{"action":"getSetting","id":"global_policy_mode","result":"success","data":{"settings":{"global_policy_mode":"enforce"}}}"#;
        let overridable = r#"{"action":"getSetting","id":"global_policy_mode_overridable","result":"success","data":{"settings":{"global_policy_mode_overridable":false}}}"#;
        assert_eq!(
            GlobalPolicyMode::parse(mode, overridable).unwrap(),
            GlobalPolicyMode {
                mode: PolicyMode::Enforce,
                overridable: false
            }
        );
        assert!(GlobalPolicyMode::parse(overridable, mode).is_err());
    }

    #[test]
    fn own_policy_mode() {
        assert_eq!(PolicyMode::parse_own(&json!("default")).unwrap(), None);
        assert_eq!(
            PolicyMode::parse_own(&json!("audit")).unwrap(),
            Some(PolicyMode::Audit)
        );
        // A mode this version does not know
        let error = PolicyMode::parse_own(&json!("strict")).unwrap_err();
        assert_eq!(error.to_string(), UNEXPECTED);
    }

    #[test]
    fn policy_mode_that_applies() {
        use PolicyMode::*;
        use PolicyModeSource::*;
        let global = |overridable| GlobalPolicyMode {
            mode: Audit,
            overridable,
        };
        assert_eq!(global(true).apply_to(Some(Enforce)), (Enforce, Node));
        assert_eq!(global(true).apply_to(None), (Audit, Global));
        // The node's own mode is ignored
        assert_eq!(global(false).apply_to(Some(Enforce)), (Audit, Forced));
        assert_eq!(global(false).apply_to(None), (Audit, Forced));
    }
}
