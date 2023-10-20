use crate::rpkg::archive;
use serde::{Deserialize, Serialize};
use std::{collections::HashMap, str};

#[derive(Serialize, Deserialize, PartialEq, Eq, Debug, Clone)]
pub struct Dependency {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub python: Option<Vec<String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub binary: Option<Vec<String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub apt: Option<Vec<String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub rpm: Option<Vec<String>>,
}

#[derive(Serialize, Deserialize, PartialEq, Eq, Debug, Clone)]
pub struct Metadata {
    #[serde(rename = "type")]
    pub plugin_type: archive::PackageType,
    pub name: String,
    pub version: String,
    #[serde(rename(serialize = "build-date", deserialize = "build-date"))]
    pub build_date: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub depends: Option<Dependency>,
    #[serde(rename(serialize = "build-commit", deserialize = "build-commit"))]
    pub build_commit: String,
    pub content: HashMap<String, String>,
    #[serde(rename(serialize = "jar-files", deserialize = "jar-files"))]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub jar_files: Option<Vec<String>>,
}
