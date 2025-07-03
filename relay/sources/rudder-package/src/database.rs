// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

use std::{
    collections::HashMap,
    fs::{self, *},
    io::BufWriter,
    path::{Path, PathBuf},
    str::FromStr,
};

use anyhow::{Context, Result, anyhow, bail};
use itertools::Itertools;
use serde::{Deserialize, Serialize};
use tracing::{debug, info, warn};

use super::archive::Rpkg;
use crate::{
    PACKAGES_FOLDER, TMP_PLUGINS_FOLDER,
    archive::{PackageScript, PackageScriptArg},
    plugin::{self, short_name},
    repo_index::RepoIndex,
    repository::Repository,
    versions::ArchiveVersion,
    webapp::Webapp,
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
        version: Option<&str>,
        repository: &Repository,
        index: Option<&RepoIndex>,
        webapp: &mut Webapp,
    ) -> Result<()> {
        info!("Installing {}", package);
        // Local install detection
        let rpkg_path = if package.ends_with(".rpkg") || package.contains('/') {
            if Path::new(&package).exists() {
                package.to_string()
            } else {
                bail!("The {} plugin file does not exist", package)
            }
        } else {
            let i = match index {
                Some(i) => i,
                None => bail!(
                    "No index was found from remote repository. Try running 'rudder package update'."
                ),
            };

            let to_dl_and_install = if let Some(v) = version {
                let parsed_version = ArchiveVersion::from_str(v)?;
                if force {
                    i.any_matching_plugin(package, &parsed_version)
                        .ok_or(anyhow!(
                            "Could not find any plugin matching '{}:{}'",
                            package,
                            v,
                        ))
                } else {
                    i.matching_compatible_plugin(&webapp.version, package, &parsed_version)
                        .ok_or(anyhow!(
                            "Could not find any compatible plugin matching '{}:{}'",
                            package,
                            v,
                        ))
                }
            } else {
                i.latest_compatible_plugin(&webapp.version, package)
                    .ok_or(anyhow!(
                        "Could not find any compatible '{}' plugin",
                        package
                    ))
            }?;

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
        if self.plugins.contains_key(&rpkg.metadata.name) {
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

    pub fn disabled_plugins_save(&self, backup_path: &Path, webapp: &mut Webapp) -> Result<()> {
        let mut disabled = Vec::new();
        let enabled_jars = webapp.jars()?;
        for (name, p) in self.plugins.iter().sorted_by_key(|x| x.0) {
            if !p.metadata.jar_files.is_empty()
                && !p
                    .metadata
                    .jar_files
                    .iter()
                    .all(|j| enabled_jars.contains(j))
            {
                disabled.push(format!("disabled {name}"));
            }
        }
        fs::write(
            backup_path,
            disabled
                .iter()
                .fold("".to_string(), |acc, s| format!("{acc}{s}\n")),
        )
        .with_context(|| {
            format!(
                "Failed to save the plugins statuses in the backup file {}",
                backup_path.to_string_lossy()
            )
        })?;
        info!(
            "Plugins statuses saved in {}",
            backup_path.to_string_lossy()
        );
        Ok(())
    }

    fn apply_plugin_status_line_from_backup(&self, line: &str, webapp: &mut Webapp) -> Result<()> {
        let mut split = line.split_whitespace();
        let status = split.next().with_context(|| {
            format!("Failed to parse the plugin status from the status backup file line '{line}'")
        })?;
        let plugin_name = split.next().with_context(|| {
            format!("Failed to parse the plugin name from the status backup file line '{line}'")
        })?;
        if plugin_name.ends_with(".jar") && plugin_name.starts_with('/') {
            match status {
                "disabled" => webapp.disable_jars(&[plugin_name.to_owned()]),
                "enabled" => webapp.enable_jars(&[plugin_name.to_owned()]),
                _ => bail!("Unexpected plugin status in the backup file: {}", line),
            }
        } else {
            let i = self.plugins.get(plugin_name).context(format!(
                "The plugin {plugin_name} is not installed, it could not be enabled."
            ))?;
            match status {
                "disabled" => i.disable(webapp),
                "enabled" => i.enable(webapp),
                _ => bail!("Unexpected plugin status in the backup file: {}", line),
            }
        }
    }

    pub fn enabled_plugins_restore(&self, backup_path: &Path, webapp: &mut Webapp) -> Result<()> {
        for line in read_to_string(backup_path)
            .with_context(|| {
                format!(
                    "Failed to read the status backup file in {}",
                    backup_path.to_string_lossy()
                )
            })?
            .lines()
        {
            if let Err(e) = self.apply_plugin_status_line_from_backup(line, webapp) {
                warn!("{:?}", e)
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
            debug!(
                "Plugin {} does not support the enable/disable feature, it will always be enabled if installed.",
                self.metadata.name
            );
            Ok(())
        } else {
            webapp.disable_jars(&self.metadata.jar_files)
        }
    }

    pub fn enable(&self, webapp: &mut Webapp) -> Result<()> {
        info!("Enabling plugin {}", self.metadata.short_name());
        if self.metadata.jar_files.is_empty() {
            debug!(
                "Plugin {} does not support the enable/disable feature, it will always be enabled if installed.",
                self.metadata.name
            );
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
    use tempfile::TempDir;

    use super::*;
    use crate::{archive, versions::RudderVersion};

    #[test]
    fn test_save_plugin_statuses() {
        let temp_dir = TempDir::new().unwrap();
        let database_path = temp_dir.path().join("database.json");
        let webapp_path = temp_dir.path().join("webappPath.json");
        let backup_path = temp_dir.path().join("backup.json");
        fs::copy(
            "tests/status_backup_file/database.json",
            database_path.clone(),
        )
        .unwrap();
        fs::copy("tests/status_backup_file/webapp.xml", webapp_path.clone()).unwrap();
        let mut w = Webapp::new(
            webapp_path,
            RudderVersion::from_path("./tests/versions/rudder-server-version").unwrap(),
        );
        let d = Database::read(Path::new(&database_path)).unwrap();
        d.disabled_plugins_save(&backup_path, &mut w).unwrap();
        assert_eq!(
            fs::read_to_string(backup_path).unwrap(),
            fs::read_to_string("./tests/status_backup_file/backup.expected").unwrap()
        );
    }

    #[test]
    fn test_apply_plugin_status_line_from_backup() {
        let temp_dir = TempDir::new().unwrap();
        let database_path = temp_dir.path().join("database.json");
        let webapp_path = temp_dir.path().join("webappPath.json");
        fs::copy(
            "tests/status_backup_file/database.json",
            database_path.clone(),
        )
        .unwrap();
        fs::copy("tests/status_backup_file/webapp.xml", webapp_path.clone()).unwrap();
        let mut w = Webapp::new(
            webapp_path,
            RudderVersion::from_path("./tests/versions/rudder-server-version").unwrap(),
        );
        let d = Database::read(Path::new(&database_path)).unwrap();
        // Enable >8.1 syntax
        d.apply_plugin_status_line_from_backup("enabled rudder-plugin-dsc", &mut w)
            .unwrap();
        assert!(
            w.jars()
                .unwrap()
                .contains(&"/opt/rudder/share/plugins/dsc/dsc.jar".to_string())
        );

        // Disable >8.1 syntax
        d.apply_plugin_status_line_from_backup("disabled rudder-plugin-dsc", &mut w)
            .unwrap();
        assert!(
            !w.jars()
                .unwrap()
                .contains(&"/opt/rudder/share/plugins/dsc/dsc.jar".to_string())
        );

        // Enable <8.0 syntax
        d.apply_plugin_status_line_from_backup(
            "enabled /opt/rudder/share/plugins/dsc/dsc.jar",
            &mut w,
        )
        .unwrap();
        assert!(
            w.jars()
                .unwrap()
                .contains(&"/opt/rudder/share/plugins/dsc/dsc.jar".to_string())
        );

        // Disable <8.0 syntax
        d.apply_plugin_status_line_from_backup(
            "disabled /opt/rudder/share/plugins/dsc/dsc.jar",
            &mut w,
        )
        .unwrap();
        assert!(
            !w.jars()
                .unwrap()
                .contains(&"/opt/rudder/share/plugins/dsc/dsc.jar".to_string())
        );

        // Unsupported plugin name syntax
        assert!(
            d.apply_plugin_status_line_from_backup("enabled dsc", &mut w)
                .is_err()
        );

        // Unsupported plugin status syntax
        assert!(
            d.apply_plugin_status_line_from_backup("eNabled rudder-plugin-dsc", &mut w)
                .is_err()
        );

        // Non installed plugin
        assert!(
            d.apply_plugin_status_line_from_backup("enabled rudder-plugin-unknown", &mut w)
                .is_err()
        );
    }

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
                requires_license: false,
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
