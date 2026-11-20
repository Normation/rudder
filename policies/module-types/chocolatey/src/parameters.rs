// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS
use anyhow::{Result, bail};
use serde::{Deserialize, Serialize};
use std::{fmt::Display, path::PathBuf, str::FromStr};

/// Package version as expressed by Rudder
/// `Any` for any version
/// `Latest` for the latest available one
/// `Exact(v)` for a specific version v
#[derive(Clone, Default, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(try_from = "String")]
pub enum RudderPackageVersion {
    Any,
    #[default]
    Latest,
    Exact(String),
}

impl FromStr for RudderPackageVersion {
    type Err = anyhow::Error;
    fn from_str(s: &str) -> Result<Self> {
        if s.is_empty() {
            bail!("Package version can not be empty")
        }
        let ls = s.to_lowercase();
        match ls.as_ref() {
            "any" => Ok(Self::Any),
            "latest" => Ok(Self::Latest),
            _ => Ok(Self::Exact(s.to_string())),
        }
    }
}

impl TryFrom<String> for RudderPackageVersion {
    type Error = anyhow::Error;

    fn try_from(s: String) -> Result<Self> {
        s.parse()
    }
}

impl From<RudderPackageVersion> for String {
    fn from(v: RudderPackageVersion) -> Self {
        match v {
            RudderPackageVersion::Any => "any".to_string(),
            RudderPackageVersion::Latest => "latest".to_string(),
            RudderPackageVersion::Exact(s) => s,
        }
    }
}

impl Display for RudderPackageVersion {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "{}",
            match self {
                RudderPackageVersion::Any => "any",
                RudderPackageVersion::Latest => "latest",
                RudderPackageVersion::Exact(v) => v,
            }
        )
    }
}

/// Package status as expressed by Rudder
#[derive(Clone, Default, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum RudderPackageStatus {
    #[default]
    Present,
    Absent,
}

/// Package entry as expressed by the chocolatey Rudder module inputs
/// `extra_params` are raw `choco` CLI command parameters added to the command execution
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct PackageEntry {
    pub name: String,
    #[serde(default)]
    pub version: RudderPackageVersion,
    #[serde(default)]
    pub status: RudderPackageStatus,
    #[serde(default)]
    pub extra_params: Vec<String>,
}

/// chocolatey Rudder module parameters
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub struct ChocoModuleParameters {
    #[serde(default)]
    pub packages: Vec<PackageEntry>,
    #[serde(default)]
    pub report_file: Option<PathBuf>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_rudder_package_version() {
        use RudderPackageVersion::{Any, Exact, Latest};
        assert_eq!(RudderPackageVersion::from_str("any").unwrap(), Any);
        assert_eq!(RudderPackageVersion::from_str("Any").unwrap(), Any);

        assert_eq!(RudderPackageVersion::from_str("latest").unwrap(), Latest);
        assert_eq!(RudderPackageVersion::from_str("Latest").unwrap(), Latest);
        assert_eq!(RudderPackageVersion::from_str("LATEST").unwrap(), Latest);

        assert_eq!(
            RudderPackageVersion::from_str("2.7.4").unwrap(),
            Exact("2.7.4".to_string())
        );
        assert_eq!(
            RudderPackageVersion::from_str("0.d~beta-2").unwrap(),
            Exact("0.d~beta-2".to_string())
        );
        assert_eq!(
            RudderPackageVersion::from_str("plouf").unwrap(),
            Exact("plouf".to_string())
        );
    }

    #[test]
    fn deserialize_rudder_package_version() {
        use RudderPackageVersion::{Any, Exact, Latest};
        assert_eq!(
            serde_json::from_str::<PackageEntry>(r#"{"name": "git", "version": "2.47.0"}"#)
                .unwrap(),
            PackageEntry {
                name: "git".to_string(),
                version: Exact("2.47.0".to_string()),
                status: RudderPackageStatus::Present,
                extra_params: vec![]
            }
        );
        assert_eq!(
            serde_json::from_str::<PackageEntry>(r#"{"name": "git", "version": "ANY"}"#).unwrap(),
            PackageEntry {
                name: "git".to_string(),
                version: Any,
                status: RudderPackageStatus::Present,
                extra_params: vec![]
            }
        );
        assert_eq!(
            serde_json::from_str::<PackageEntry>(r#"{"name": "git", "version": "Latest"}"#)
                .unwrap(),
            PackageEntry {
                name: "git".to_string(),
                version: Latest,
                status: RudderPackageStatus::Present,
                extra_params: vec![]
            }
        );
    }
    #[test]
    fn reads_report_file_from_agent_data() -> anyhow::Result<()> {
        let p: ChocoModuleParameters = serde_json::from_str(
            r#"{"packages":[{"extra_params":[""],"name":"vim","status":"present","version":"any"}],
              "report_file":"C:\\Program Files\\Rudder\\tmp\\chocolatey_module_report.log"}"#,
        )?;
        assert_eq!(
            p.report_file,
            Some(PathBuf::from(
                r"C:\Program Files\Rudder\tmp\chocolatey_module_report.log"
            ))
        );
        Ok(())
    }
}
