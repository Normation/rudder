use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use std::{collections::HashMap, fs::*, io::BufWriter};

use crate::rpkg::plugin;

#[derive(Serialize, Deserialize, PartialEq, Eq, Debug, Clone)]
pub struct Database {
    pub plugins: HashMap<String, InstalledPlugin>,
}

impl Database {
    pub fn read(path: &str) -> Result<Database> {
        let data = std::fs::read_to_string(path)
            .with_context(|| format!("Failed to read the installed plugin database in {}", path))?;
        let database: Database = serde_json::from_str(&data)?;
        Ok(database)
    }

    pub fn write(path: &str, index: Database) -> Result<()> {
        let file = File::create(path)?;
        let mut writer = BufWriter::new(file);
        serde_json::to_writer_pretty(&mut writer, &index)
            .with_context(|| format!("Failed to update the installed plugins database {}", path))?;
        Ok(())
    }
}

#[derive(Serialize, Deserialize, PartialEq, Eq, Debug, Clone)]
pub struct InstalledPlugin {
    pub files: Vec<String>,

    #[serde(flatten)]
    pub metadata: plugin::Metadata,
}

#[cfg(test)]
mod tests {
    use super::*;
    use ::std::fs::read_to_string;
    use assert_json_diff::assert_json_eq;
    use pretty_assertions::assert_eq;
    use tempfile::TempDir;

    #[test]
    fn test_plugin_database_parsing() {
        let data = read_to_string("./tests/plugin_database_parsing.json")
            .expect("Unable to parse file './tests/pluginèdatabase_parsing.json'");
        let db: Database = serde_json::from_str(&data).unwrap();
        assert_eq!(
            db.plugins["rudder-plugin-aix"].metadata.plugin_type,
            "plugin"
        );
    }

    #[test]
    fn test_adding_a_plugin_to_db() {
        let mut a = Database::read("./tests/database/plugin_database_update_sample.json").unwrap();
        let addon = InstalledPlugin {
            files: vec![String::from("/tmp/my_path")],
            metadata: plugin::Metadata {
                plugin_type: String::from("plugin"),
                name: String::from("my_name"),
                version: String::from("0.0.0.0"),
                build_date: String::from("2023-10-13T10:03:34+00:00"),
                depends: None,
                build_commit: String::from("2abc53fb8b2d1c667a91b1a1da2f941a99872cdf"),
                content: HashMap::from([(
                    String::from("files.txz"),
                    String::from("/opt/rudder/share/plugins"),
                )]),
                jar_files: None,
            },
        };
        a.plugins.insert(addon.metadata.name.clone(), addon);
        let dir = TempDir::new().unwrap();
        let target_path = dir
            .path()
            .join("target.json")
            .into_os_string()
            .into_string()
            .unwrap();
        let _ = Database::write(&target_path.clone(), a);
        let reference: serde_json::Value = serde_json::from_str(
            &read_to_string("./tests/database/plugin_database_update_sample.json.expected")
                .unwrap(),
        )
        .unwrap();
        let generated: serde_json::Value =
            serde_json::from_str(&read_to_string(target_path).unwrap()).unwrap();
        assert_json_eq!(reference, generated);
    }
}
