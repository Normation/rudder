// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

use std::{
    collections::HashMap,
    fs::{self, *},
    io::BufWriter,
    path::PathBuf,
};

use anyhow::{anyhow, Context, Result};
use serde::{Deserialize, Serialize};

use super::archive::Rpkg;
use crate::{
    archive::{PackageScript, PackageScriptArg},
    plugin,
    webapp::Webapp,
    PACKAGES_DATABASE_PATH, PACKAGES_FOLDER,
};
use log::debug;

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
        debug!("Updating the installed plugin database in '{}'", path);
        let file = File::create(path)?;
        let mut writer = BufWriter::new(file);
        serde_json::to_writer_pretty(&mut writer, &index)
            .with_context(|| format!("Failed to update the installed plugins database {}", path))?;
        Ok(())
    }

    pub fn is_installed(&self, r: Rpkg) -> bool {
        match self.plugins.get(&r.metadata.name) {
            None => false,
            Some(installed) => installed.metadata.version == r.metadata.version,
        }
    }

    /// Return the plugin containing a given jar
    pub fn plugin_provides_jar(&self, jar: String) -> Option<&InstalledPlugin> {
        self.plugins.values().find(|p| {
            p.metadata
                .jar_files
                .as_ref()
                .map(|j| dbg!(j).contains(dbg!(&jar)))
                .unwrap_or(false)
        })
    }

    pub fn uninstall(&mut self, plugin_name: &str, webapp: &mut Webapp) -> Result<()> {
        // Force to use plugin long qualified name
        if !plugin_name.starts_with("rudder-plugin-") {
            plugin_name.to_owned().insert_str(0, "rudder-plugin-{}")
        };
        // Return Ok if not installed
        if !self.plugins.contains_key(plugin_name) {
            debug!("Plugin {} is not installed.", plugin_name);
            return Ok(());
        }
        debug!("Uninstalling plugin {}", plugin_name);
        // Disable the jar files if any
        let installed_plugin = self.plugins.get(plugin_name).ok_or(anyhow!(
            "Could not extract data for plugin {} in the database",
            plugin_name
        ))?;
        installed_plugin.disable(webapp)?;
        installed_plugin
            .metadata
            .run_package_script(PackageScript::Prerm, PackageScriptArg::None)?;
        match installed_plugin.remove_installed_files() {
            Ok(()) => (),
            Err(e) => debug!("{}", e),
        }
        installed_plugin
            .metadata
            .run_package_script(PackageScript::Postrm, PackageScriptArg::None)?;
        // Remove associated package scripts and plugin folder
        fs::remove_dir_all(
            PathBuf::from(PACKAGES_FOLDER).join(installed_plugin.metadata.name.clone()),
        )?;
        // Update the database
        self.plugins.remove(plugin_name);
        Database::write(PACKAGES_DATABASE_PATH, self.to_owned())?;
        Ok(())
    }
}

#[derive(Serialize, Deserialize, PartialEq, Eq, Debug, Clone)]
pub struct InstalledPlugin {
    pub files: Vec<String>,

    #[serde(flatten)]
    pub metadata: plugin::Metadata,
}

impl InstalledPlugin {
    pub fn disable(&self, webapp: &mut Webapp) -> Result<()> {
        debug!("Disabling plugin {}", self.metadata.name);
        match &self.metadata.jar_files {
            None => {
                println!("Plugin {} does not support the enable/disable feature, it will always be enabled if installed.", self.metadata.name);
                Ok(())
            }
            Some(jars) => webapp.disable_jars(jars),
        }
    }
    pub fn enable(&self, webapp: &mut Webapp) -> Result<()> {
        debug!("Enabling plugin {}", self.metadata.name);
        match &self.metadata.jar_files {
            None => {
                println!("Plugin {} does not support the enable/disable feature, it will always be enabled if installed.", self.metadata.name);
                Ok(())
            }
            Some(jars) => webapp.enable_jars(jars),
        }
    }

    pub fn remove_installed_files(&self) -> Result<()> {
        self.files.clone().into_iter().try_for_each(|f| {
            let m = PathBuf::from(f.clone());
            if m.is_dir() {
                debug!("Removing file '{}'", f);
                fs::remove_dir(f).map_err(anyhow::Error::from)
            } else {
                debug!("Removing folder '{}' if empty", f);
                fs::remove_file(f).map_err(anyhow::Error::from)
            }
        })
    }
}

#[cfg(test)]
mod tests {
    use std::str::FromStr;

    use ::std::fs::read_to_string;
    use assert_json_diff::assert_json_eq;
    use pretty_assertions::assert_eq;
    use tempfile::TempDir;

    use super::*;
    use crate::archive;

    #[test]
    fn test_plugin_database_parsing() {
        let data = read_to_string("./tests/plugin_database_parsing.json")
            .expect("Unable to parse file './tests/pluginèdatabase_parsing.json'");
        let db: Database = serde_json::from_str(&data).unwrap();
        assert_eq!(
            db.plugins["rudder-plugin-aix"].metadata.plugin_type,
            archive::PackageType::Plugin
        );
    }

    #[test]
    fn test_adding_a_plugin_to_db() {
        use crate::versions;

        let mut a = Database::read("./tests/database/plugin_database_update_sample.json").unwrap();
        let addon = InstalledPlugin {
            files: vec![String::from("/tmp/my_path")],
            metadata: plugin::Metadata {
                plugin_type: archive::PackageType::Plugin,
                name: String::from("my_name"),
                version: versions::ArchiveVersion::from_str("0.0.0-0.0").unwrap(),
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
