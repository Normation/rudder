// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use anyhow::{Result, bail};
use bytesize::ByteSize;
use serde::{Deserialize, Serialize};
use serde_inline_default::serde_inline_default;
use serde_with::serde_as;
use std::path::PathBuf;

/// Parameters for the augeas module, passed by the agent
#[derive(Clone, Debug, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub struct CfengineAugeasParameters {
    pub script: String,
    pub if_script: String,
    pub path: PathBuf,
    pub show_file_content: bool,
    pub lens: String,
    pub max_file_size: ByteSize,
    pub report_file: PathBuf,
}

impl From<CfengineAugeasParameters> for AugeasParameters {
    fn from(s: CfengineAugeasParameters) -> Self {
        AugeasParameters {
            script: s.script,
            if_script: s.if_script,
            context: None,
            path: s.path,
            show_file_content: false,
            lens: if s.lens.is_empty() {
                None
            } else {
                Some(s.lens)
            },
            max_file_size: s.max_file_size,
            report_file: Some(s.report_file),
        }
    }
}

/// Parameters for the augeas module.
#[derive(Clone, Debug, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
#[serde_inline_default]
#[serde_as]
pub struct AugeasParameters {
    /// Expressions to run
    pub script: String,
    // only_if
    #[serde(default)]
    pub if_script: String,
    /// Prefix to add to all expressions.
    ///
    /// By default, the `path` is used as context.
    #[serde(default)]
    #[serde_as(as = "NoneAsEmptyString")]
    pub context: Option<String>,
    /// Output file `path`
    #[serde(default)]
    // used as incl
    pub path: PathBuf,
    /// Show the diff.
    ///
    /// Enabled by default. Disable for files containing secrets.
    #[serde_inline_default(true)]
    pub show_file_content: bool,
    /// A lens to use.
    ///
    /// If not passed, all lenses are loaded, and the `path` is used
    /// to detect the lens to use.
    /// Passing a lens makes the call faster as it avoids having to
    /// load all lenses.
    #[serde(skip_serializing_if = "Option::is_none")]
    #[serde_as(as = "NoneAsEmptyString")]
    pub lens: Option<String>,

    //pub must_exist: Option<bool>,
    /// Maximal allowed file size for loading.
    #[serde_inline_default(ByteSize::mb(10))]
    pub max_file_size: ByteSize,

    /// Where to write the report.
    ///
    /// If not set, no report is written.
    ///
    /// This is needed as the custom promise type protocol only supports the outcome status and logs.
    #[serde(default)]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub report_file: Option<PathBuf>,
}

impl AugeasParameters {
    /// Validate the parameters.
    pub fn validate(&self) -> Result<()> {
        if self.path.is_relative() {
            bail!("path must be absolute: {}", self.path.display());
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_validate() {
        let relative = AugeasParameters {
            path: PathBuf::from("relative"),
            ..Default::default()
        };
        let absolute = AugeasParameters {
            path: PathBuf::from("/etc/absolute"),
            ..Default::default()
        };

        assert!(relative.validate().is_err());
        assert!(absolute.validate().is_ok());
    }
}
