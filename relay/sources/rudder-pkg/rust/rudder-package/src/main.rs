use std::{
    collections::HashMap,
    fs,
};

use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize)]
struct PluginMetadata {
    #[serde(rename(serialize = "type", deserialize = "type"))]
    plugin_type: String,
    name: String,
    version: String,
    #[serde(rename(serialize = "build-date", deserialize = "build-date"))]
    build_date: String,
    depends: Option<InstalledPluginDependency>,
    #[serde(rename(serialize = "build-commit", deserialize = "build-commit"))]
    build_commit: String,
    content: HashMap<String, String>,
}

#[derive(Serialize, Deserialize)]
struct RepoIndex(Vec<PluginInRepoIndex>);

#[derive(Serialize, Deserialize)]
struct PluginInRepoIndex {
    path: String,

    #[serde(flatten)]
    metadata: PluginMetadata,
}
 
#[derive(Serialize, Deserialize)]
struct InstalledPluginDatabase {
    plugins: HashMap<String, InstalledPlugin>,
}

#[derive(Serialize, Deserialize)]
struct InstalledPlugin {
    files: Vec<String>,
    #[serde(rename(serialize = "jar-files", deserialize = "jar-files"))]
    jar_files: Option<Vec<String>>,

    #[serde(flatten)]
    metadata: PluginMetadata,
}

#[derive(Serialize, Deserialize)]
struct InstalledPluginDependency {
    python: Option<Vec<String>>,
    binary: Option<Vec<String>>,
    apt: Option<Vec<String>>,
    rpm: Option<Vec<String>>,
}

fn main() {
}

#[cfg(test)]
mod tests {
  use super::*;

  #[test]
  fn test_plugin_database_parsing() {
    let data = fs::read_to_string("./tests/plugin_database_parsing.json").expect("Unable to parse file './tests/pluginèdatabase_parsing.json'");
    let db: InstalledPluginDatabase = serde_json::from_str(&data).unwrap();
    assert_eq!(db.plugins["rudder-plugin-aix"].metadata.plugin_type, "plugin");
  }

  #[test]
  fn test_plugin_index_parsing() {
    let data = fs::read_to_string("./tests/repo_index.json").expect("Unable to parse file './tests/repo_index.json'");
    let index: RepoIndex = serde_json::from_str(&data).unwrap();
    assert_eq!(index.0[0].metadata.version, "8.0.0~beta2-2.1");
  }
}