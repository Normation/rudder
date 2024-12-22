// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use crate::RUDDER_LENS_LIB;
use anyhow::{anyhow, Result};
use rudder_module_type::PolicyMode;
use serde::{Deserialize, Serialize};
use serde_inline_default::serde_inline_default;
use std::borrow::Cow;
use std::iter;

/// Parameters for the augeas module.
#[derive(Clone, Debug, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
#[serde_inline_default]
pub struct AugeasParameters {
    /// Expressions to run
    pub script: String,
    // only_if
    pub if_script: String,
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
    pub fn lens_name(&self) -> Option<Cow<str>> {
        match self.lens.as_deref() {
            Some(lens) => {
                if lens.ends_with(".lns") {
                    Some(Cow::from(lens))
                } else {
                    Some(Cow::from(format!("{lens}.lns")))
                }
            }
            None => None,
        }
    }

    pub fn context(&self) -> Option<Cow<str>> {
        match (self.context.as_deref(), self.path.as_deref()) {
            (Some(context), _) => Some(Cow::from(context)),
            (None, Some(p)) => Some(Cow::from(format!("files/{p}"))),
            (_, None) => None,
        }
    }

    /// Validate the parameters.
    pub fn validate(&self, _policy_mode: Option<PolicyMode>) -> Result<()> {
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
        let result = p.lens_name().unwrap();
        assert_eq!(result, "test_lens.lns");
    }

    #[test]
    fn it_does_not_append_lns_extension_if_present() {
        let lens = "test_lens.lns".to_string();
        let p = AugeasParameters {
            lens: Some(lens),
            ..Default::default()
        };
        let result = p.lens_name().unwrap();
        assert_eq!(result, "test_lens.lns");
    }
}
