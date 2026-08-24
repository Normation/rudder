// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use anyhow::{Result, bail};
use bytesize::ByteSize;
use serde::{Deserialize, Serialize};
use serde_inline_default::serde_inline_default;
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
// `serde_inline_default` must come before `derive`, otherwise the derive runs
// first and never sees the defaults it generates.
#[serde_inline_default]
#[derive(Clone, Debug, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
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

    #[test]
    fn it_defaults_the_optional_parameters() {
        let p: AugeasParameters = serde_json::from_str(r#"{"script":"s"}"#).unwrap();
        assert!(p.show_file_content);
        assert_eq!(p.max_file_size, ByteSize::mb(10));
        // and they can still be set
        let p: AugeasParameters = serde_json::from_str(
            r#"{"script":"s","show_file_content":false,"max_file_size":"1 MB"}"#,
        )
        .unwrap();
        assert!(!p.show_file_content);
        assert_eq!(p.max_file_size, ByteSize::mb(1));
    }

    #[test]
    fn it_parses_optional_string_parameters() {
        let parse = |extra: &str| -> AugeasParameters {
            serde_json::from_str(&format!(r#"{{"script":"s"{extra}}}"#)).unwrap()
        };

        let p = parse("");
        assert_eq!(p.context, None);
        assert_eq!(p.lens, None);

        let p = parse(r#","context":null,"lens":null"#);
        assert_eq!(p.context, None);
        assert_eq!(p.lens, None);

        let p = parse(r#","context":"","lens":"""#);
        assert_eq!(p.context.as_deref(), Some(""));
        assert_eq!(p.lens.as_deref(), Some(""));

        let p = parse(r#","context":"/files","lens":"Hosts""#);
        assert_eq!(p.context.as_deref(), Some("/files"));
        assert_eq!(p.lens.as_deref(), Some("Hosts"));

        assert!(serde_json::from_str::<AugeasParameters>(r#"{"script":"s","lens":42}"#).is_err());
    }

    #[test]
    fn it_serializes_optional_string_parameters() {
        let json = serde_json::to_string(&AugeasParameters::default()).unwrap();
        assert!(json.contains(r#""context":null"#), "{json}");
        // `lens` is skipped when unset
        assert!(!json.contains("lens"), "{json}");
    }
}
