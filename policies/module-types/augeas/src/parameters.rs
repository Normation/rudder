// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use crate::RUDDER_LENS_LIB;
use anyhow::{anyhow, Result};
use rudder_module_type::PolicyMode;
use serde::{Deserialize, Serialize};
use serde_inline_default::serde_inline_default;
use std::iter;

/// Parameters for the augeas module.
///
/// Only one of `commands`, `changes+path+(lens)` or `checks+path+(lens)` can be used.
#[derive(Clone, Debug, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
#[serde_inline_default]
pub struct AugeasParameters {
    // force ?? FIXME: check why, force l'écriture même sans changement détectés
    /// Raw commands to run.
    ///
    /// Should be used with care, as it can lead to override any configuration, including
    /// making changes in audit mode.
    pub commands: Vec<String>,
    pub changes: Vec<String>,
    // changes
    // same syntax as only if?
    pub checks: Vec<String>,
    // only_if
    pub condition: Vec<String>,
    /// Prefix to add.
    ///
    /// By default,
    ///
    /// * If a `path` is passed, it is used as context
    /// * If no `path` is passed, the context is empty.
    #[serde(default)]
    pub context: Option<String>,
    /// Output file path
    #[serde(default)]
    // used as incl
    pub path: Option<String>,
    /// Augeas root
    ///
    /// The root of the filesystem to use for augeas.
    ///
    /// WARNING: Should not be used in most cases.
    #[serde(default)]
    pub root: Option<String>,
    /// Show the diff.
    ///
    /// Enabled by default. Disable for files containing secrets.
    #[serde_inline_default(true)]
    pub show_diff: bool,
    /// Additional load paths for lenses.
    ///
    /// `/var/rudder/lib/lenses` is always added.
    pub lens_paths: Vec<String>,
    /// A lens to use.
    ///
    /// If not passed, all lenses are loaded, and the `path` is used
    /// to detect the lens to use.
    /// Passing a lens makes the call faster as it avoids having to
    /// load all lenses.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub lens: Option<String>,
    /// Force augeas to type-check the lenses.
    ///
    /// This is slow and should only be used for debugging.
    #[serde_inline_default(false)]
    pub type_check_lenses: bool,
}

impl AugeasParameters {
    pub fn load_paths(&self) -> String {
        // Load from the given paths plus the default one.
        self.lens_paths
            .iter()
            .map(|p| p.as_str())
            .chain(iter::once(RUDDER_LENS_LIB))
            .collect::<Vec<&str>>()
            .join(":")
    }

    /// Returns the lens name with the `.lns` extension if missing.
    pub fn lens(&self) -> Option<String> {
        match self.lens.as_ref() {
            Some(lens) => {
                if lens.ends_with(".lns") {
                    Some(lens.clone())
                } else {
                    Some(format!("{lens}.lns"))
                }
            }
            None => None,
        }
    }

    pub fn validate(&self, policy_mode: Option<PolicyMode>) -> Result<()> {
        if !self.commands.is_empty() {
            if let Some(p) = policy_mode {
                if p != PolicyMode::Enforce {
                    return Err(anyhow!("`commands` can only be used in enforce mode"));
                }
            }

            if !self.changes.is_empty() {
                return Err(anyhow!("Cannot use both `commands` and `changes`"));
            }
            if !self.checks.is_empty() {
                return Err(anyhow!("Cannot use both `commands` and `checks`"));
            }
            if self.path.is_some() {
                return Err(anyhow!("Cannot use both `commands` and `path`"));
            }
            if self.lens.is_some() {
                return Err(anyhow!("Cannot use both `commands` and `lens`"));
            }
        }

        if !self.changes.is_empty() && !self.checks.is_empty() {
            return Err(anyhow!("Cannot use both `changes` and `checks`"));
        }
        if self.changes.is_empty() && self.checks.is_empty() {
            return Err(anyhow!("One of `changes` or `checks` must be used"));
        }

        if self.path.is_none() {
            return Err(anyhow!("`path` is required when not in `commands` mode"));
        }

        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn it_appends_lns_extension_if_missing() {
        let lens = "test_lens".to_string();
        let p = AugeasParameters {
            lens: Some(lens),
            ..Default::default()
        };
        let result = p.lens().unwrap();
        assert_eq!(result, "test_lens.lns");
    }

    #[test]
    fn it_does_not_append_lns_extension_if_present() {
        let lens = "test_lens.lns".to_string();
        let p = AugeasParameters {
            lens: Some(lens),
            ..Default::default()
        };
        let result = p.lens().unwrap();
        assert_eq!(result, "test_lens.lns");
    }
}
