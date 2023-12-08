// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

use std::{
    collections::HashMap,
    fs::{self, *},
    io::BufWriter,
    path::{Path, PathBuf},
};

use anyhow::{anyhow, bail, Context, Result};
use serde::{Deserialize, Serialize};
use tracing::{debug, info};

use super::archive::Rpkg;
use crate::{
    archive::{PackageScript, PackageScriptArg},
    plugin::{self, short_name},
    repo_index::RepoIndex,
    repository::Repository,
    webapp::Webapp,
    PACKAGES_FOLDER, TMP_PLUGINS_FOLDER,
};

#[derive(Serialize, Deserialize, PartialEq, Eq, Debug, Clone)]
pub struct Database {
    #[serde(skip)]
    path: PathBuf,
    pub plugins: HashMap<String, InstalledPlugin>,
}

impl Database {
    pub fn read(path: &Path) -> Result<Database> {
        Ok(if path.exists() {
            let data = std::fs::read_to_string(path).with_context(|| {
                format!(
                    "Failed to read the installed plugin database in {}",
                    path.display()
                )
            })?;
            let mut db: Database = serde_json::from_str(&data)?;
            db.path = path.to_path_buf();
            db
        } else {
            debug!("No database yet, using an empty one");
            Self {
                path: path.to_path_buf(),
                plugins: HashMap::new(),
            }
        })
    }

    pub fn insert(&mut self, k: String, v: InstalledPlugin) -> Result<()> {
        self.plugins.insert(k, v);
        self.write()
    }

    pub fn write(&mut self) -> Result<()> {
        debug!(
            "Updating the installed plugin database in '{}'",
            self.path.display()
        );
        create_dir_all(self.path.parent().unwrap())?;
        let file = fs::OpenOptions::new()
            .write(true)
            .create(true)
            .truncate(true)
            .open(&self.path)?;
        let mut writer = BufWriter::new(file);
        serde_json::to_writer_pretty(&mut writer, &self).with_context(|| {
            format!(
                "Failed to update the installed plugins database {}",
                self.path.display()
            )
        })?;
        Ok(())
    }

    pub fn is_installed(&self, r: &Rpkg) -> bool {
        match self.plugins.get(&r.metadata.name) {
            None => false,
            Some(installed) => installed.metadata.version == r.metadata.version,
        }
    }

    /// Return the plugin containing a given jar
    pub fn plugin_provides_jar(&self, jar: &String) -> Option<&InstalledPlugin> {
        self.plugins
            .values()
            .find(|p| p.metadata.jar_files.contains(jar))
    }

    pub fn install(
        &mut self,
        force: bool,
        package: &str,
        repository: &Repository,
        index: Option<&RepoIndex>,
        webapp: &mut Webapp,
    ) -> Result<()> {
        let rpkg_path = if Path::new(&package).exists() && package.ends_with(".rpkg") {
            package.to_string()
        } else {
            // Find compatible plugin if any
            let to_dl_and_install = match index.and_then(|i|i.latest_compatible_plugin(&webapp.version, package)) {
                    None => bail!("Could not find any compatible '{}' plugin with the current Rudder version in the configured repository.", package),
                    Some(p) => {
                        debug!("Found a compatible plugin in the repository:\n{:?}", p);
                        p
                    }
                };
            let dest = Path::new(TMP_PLUGINS_FOLDER).join(
                Path::new(&to_dl_and_install.path)
                    .file_name()
                    .ok_or_else(|| {
                        anyhow!(
                            "Could not retrieve filename from path '{}'",
                            to_dl_and_install.path
                        )
                    })?,
            );
            // Download rpkg
            repository.download(&to_dl_and_install.path, &dest)?;
            dest.as_path().display().to_string()
        };
        let rpkg = Rpkg::from_path(&rpkg_path)?;
        if self.plugins.get(&rpkg.metadata.name).is_some() {
            info!(
                "Plugin {} already installed, upgrading",
                rpkg.metadata.short_name()
            );
        }
        rpkg.install(force, self, webapp)?;
        Ok(())
    }

    pub fn uninstall(
        &mut self,
        plugin_name: &str,
        run_rm_scripts: bool,
        webapp: &mut Webapp,
    ) -> Result<()> {
        let short_name = short_name(plugin_name);
        // Return Ok if not installed
        if !self.plugins.contains_key(plugin_name) {
            info!("Plugin {} is not installed.", short_name);
            return Ok(());
        }
        // Disable the jar files if any
        let installed_plugin = self.plugins.get(plugin_name).ok_or(anyhow!(
            "Could not extract data for plugin {} in the database",
            short_name
        ))?;
        debug!(
            "Uninstalling plugin {} (version {})",
            short_name, installed_plugin.metadata.version
        );
        installed_plugin.disable(webapp)?;
        if run_rm_scripts {
            installed_plugin
                .metadata
                .run_package_script(PackageScript::Prerm, PackageScriptArg::None)?;
        }
        match installed_plugin.remove_installed_files() {
            Ok(()) => (),
            Err(e) => debug!("{}", e),
        }
        if run_rm_scripts {
            installed_plugin
                .metadata
                .run_package_script(PackageScript::Postrm, PackageScriptArg::None)?;
        }
        // Remove associated package scripts and plugin folder
        let plugin_dir = PathBuf::from(PACKAGES_FOLDER).join(&installed_plugin.metadata.name);
        if plugin_dir.exists() {
            fs::remove_dir_all(&plugin_dir).context(format!(
                "Could not remove {} plugin folder '{}'",
                short_name,
                plugin_dir.display()
            ))?;
        }
        // Update the database
        self.plugins.remove(plugin_name);
        self.write()?;
        info!("Plugin {} successfully uninstalled", short_name);
        Ok(())
    }

    pub fn enabled_plugins_save(&self, backup_path: &Path, webapp: &mut Webapp) -> Result<()> {
        let saved = webapp
            .jars()?
            .iter()
            // Let's ignore unknown jars
            .flat_map(|j| self.plugin_provides_jar(j))
            .map(|p| format!("enable {}", p.metadata.name))
            .collect::<Vec<String>>()
            .join("\n");
        fs::write(backup_path, saved)?;
        Ok(())
    }

    pub fn enabled_plugins_restore(&self, backup_path: &Path, webapp: &mut Webapp) -> Result<()> {
        for line in read_to_string(backup_path)?.lines() {
            let plugin_name = line.trim().split(' ').nth(1);
            match plugin_name {
                None => debug!("Malformed line in plugin backup status file"),
                Some(x) => match self.plugins.get(x) {
                    None => debug!("Plugin {} is not installed, it could not be enabled", x),
                    Some(y) => y.enable(webapp)?,
                },
            }
        }
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
        info!("Disabling plugin {}", self.metadata.short_name());
        if self.metadata.jar_files.is_empty() {
            debug!("Plugin {} does not support the enable/disable feature, it will always be enabled if installed.", self.metadata.name);
            Ok(())
        } else {
            webapp.disable_jars(&self.metadata.jar_files)
        }
    }

    pub fn enable(&self, webapp: &mut Webapp) -> Result<()> {
        info!("Enabling plugin {}", self.metadata.short_name());
        if self.metadata.jar_files.is_empty() {
            debug!("Plugin {} does not support the enable/disable feature, it will always be enabled if installed.", self.metadata.name);
            Ok(())
        } else {
            webapp.enable_jars(&self.metadata.jar_files)
        }
    }

    pub fn remove_installed_files(&self) -> Result<()> {
        // Remove by decreasing path length to
        // empty directories before trying to remove them.
        let mut to_remove = self.files.clone();
        to_remove.sort_by(|a, b| b.len().partial_cmp(&a.len()).unwrap());
        to_remove.into_iter().try_for_each(|f| {
            let m = PathBuf::from(f.clone());
            if m.is_dir() {
                let is_empty = m.read_dir()?.next().is_none();
                if is_empty {
                    debug!("Removing folder '{}'", f);
                    fs::remove_dir(f).map_err(anyhow::Error::from)
                } else {
                    debug!("Not removing folder '{}' as it's not empty", f);
                    Ok(())
                }
            } else {
                debug!("Removing file '{}'", f);
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

    use super::*;
    use crate::archive;

    #[test]
    fn test_plugin_database_parsing() {
        let data = read_to_string("./tests/plugin_database_parsing.json")
            .expect("Unable to parse file './tests/pluginèdatabase_parsing.json'");
        let db: Database = serde_json::from_str(&data).unwrap();
        assert_eq!(
            db.plugins["rudder-plugin-aix"].metadata.package_type,
            archive::PackageType::Plugin
        );
        for p in db.plugins {
            println!("{}", p.1.metadata);
        }
    }

    #[test]
    fn test_adding_a_plugin_to_db() {
        use crate::versions;

        fs::copy(
            "./tests/database/plugin_database_update_sample.json",
            "./tests/database/plugin_database_update_sample.json.test",
        )
        .unwrap();
        let mut a = Database::read(Path::new(
            "./tests/database/plugin_database_update_sample.json.test",
        ))
        .unwrap();
        let addon = InstalledPlugin {
            files: vec![String::from("/tmp/my_path")],
            metadata: plugin::Metadata {
                package_type: archive::PackageType::Plugin,
                name: String::from("my_name"),
                description: None,
                version: versions::ArchiveVersion::from_str("0.0.0-0.0").unwrap(),
                build_date: String::from("2023-10-13T10:03:34+00:00"),
                depends: None,
                build_commit: String::from("2abc53fb8b2d1c667a91b1a1da2f941a99872cdf"),
                content: HashMap::from([(
                    String::from("files.txz"),
                    String::from("/opt/rudder/share/plugins"),
                )]),
                jar_files: vec![],
            },
        };
        a.insert(addon.metadata.name.clone(), addon).unwrap();
        let reference: serde_json::Value = serde_json::from_str(
            &read_to_string("./tests/database/plugin_database_update_sample.json.expected")
                .unwrap(),
        )
        .unwrap();
        let generated: serde_json::Value = serde_json::from_str(
            &read_to_string("./tests/database/plugin_database_update_sample.json.test").unwrap(),
        )
        .unwrap();
        fs::remove_file("./tests/database/plugin_database_update_sample.json.test").unwrap();
        assert_json_eq!(reference, generated);
    }
}
