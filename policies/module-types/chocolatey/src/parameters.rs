use anyhow::{Result, bail};
use serde::{Deserialize, Serialize};
use std::{path::PathBuf, str::FromStr};

/// Package version as expressed by Rudder
/// `Any` for any version
/// `Latest` for the latest available one
/// `Exact(v)` for a specific version v
#[derive(Clone, Default, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
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

impl From<RudderPackageVersion> for String {
    fn from(v: RudderPackageVersion) -> Self {
        match v {
            RudderPackageVersion::Any => "any".to_string(),
            RudderPackageVersion::Latest => "latest".to_string(),
            RudderPackageVersion::Exact(s) => s,
        }
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
    pub _report_file: Option<PathBuf>,
}
