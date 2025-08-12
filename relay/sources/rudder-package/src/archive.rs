// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

use core::fmt;
use std::{
    env,
    fs::{self, *},
    io::{Cursor, Read},
    path::PathBuf,
};

use anyhow::{Context, Ok, Result, anyhow, bail};
use ar::Archive;
use serde::{Deserialize, Serialize};
use tracing::{debug, info};

use crate::{
    DONT_RUN_POSTINST_ENV_VAR, PACKAGE_SCRIPTS_ARCHIVE, PACKAGES_FOLDER,
    database::{Database, InstalledPlugin},
    plugin::Metadata,
    webapp::Webapp,
};

#[derive(Serialize, Deserialize, PartialEq, Eq, Debug, Clone, Copy)]
pub enum PackageType {
    #[serde(rename = "plugin")]
    Plugin,
}

impl fmt::Display for PackageType {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        match self {
            PackageType::Plugin => write!(f, "plugin"),
        }
    }
}

#[derive(Debug, Clone, Copy)]
pub enum PackageScript {
    Postinst,
    Postrm,
    Preinst,
    Prerm,
}

impl fmt::Display for PackageScript {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        match self {
            PackageScript::Postinst => write!(f, "postinst"),
            PackageScript::Postrm => write!(f, "postrm"),
            PackageScript::Preinst => write!(f, "preinst"),
            PackageScript::Prerm => write!(f, "prerm"),
        }
    }
}

#[derive(Debug, Clone, Copy)]
pub enum PackageScriptArg {
    Install,
    Upgrade,
    None,
}

impl fmt::Display for PackageScriptArg {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        match self {
            PackageScriptArg::Install => write!(f, "install"),
            PackageScriptArg::Upgrade => write!(f, "upgrade"),
            PackageScriptArg::None => write!(f, ""),
        }
    }
}

#[derive(Clone)]
pub struct Rpkg {
    pub path: PathBuf,
    pub metadata: Metadata,
}

impl Rpkg {
    pub fn from_path(path: &str) -> Result<Rpkg> {
        Ok(Self {
            path: PathBuf::from(path),
            metadata: read_metadata(path)?,
        })
    }

    fn get_txz_dst(&self, txz_name: &str) -> PathBuf {
        // Build the destination path
        if txz_name == PACKAGE_SCRIPTS_ARCHIVE {
            return PathBuf::from(PACKAGES_FOLDER).join(self.metadata.name.clone());
        }
        PathBuf::from(self.metadata.content.get(txz_name).unwrap().to_string())
    }

    fn get_archive_installed_files(&self) -> Result<Vec<String>> {
        let mut txz_names = self.get_txz_list()?;
        txz_names.retain(|x| x != PACKAGE_SCRIPTS_ARCHIVE);
        let f = txz_names
            .iter()
            .map(|x| self.get_absolute_file_list_of_txz(x))
            .collect::<Result<Vec<_>>>()?;
        Ok(f.into_iter().flatten().collect::<Vec<String>>())
    }

    fn get_txz_list(&self) -> Result<Vec<String>> {
        let mut txz_names = Vec::<String>::new();
        let mut archive = Archive::new(File::open(self.path.clone()).unwrap());
        while let Some(entry_result) = archive.next_entry() {
            let name = std::str::from_utf8(entry_result?.header().identifier())?.to_string();
            if name.ends_with(".txz") {
                txz_names.push(name);
            }
        }
        Ok(txz_names)
    }

    fn get_relative_file_list_of_txz(&self, txz_name: &str) -> Result<Vec<String>> {
        let mut file_list = Vec::<String>::new();
        let mut archive = Archive::new(File::open(self.path.clone()).unwrap());
        while let Some(entry_result) = archive.next_entry() {
            let txz_archive = entry_result.unwrap();
            let entry_title = std::str::from_utf8(txz_archive.header().identifier()).unwrap();
            if entry_title != txz_name {
                continue;
            }
            let mut unxz_archive = Vec::new();
            let mut f = std::io::BufReader::new(txz_archive);
            lzma_rs::xz_decompress(&mut f, &mut unxz_archive)?;
            let mut tar_archive = tar::Archive::new(Cursor::new(unxz_archive));
            tar_archive
                .entries()?
                .filter_map(|e| e.ok())
                .for_each(|entry| {
                    let a = entry
                        .path()
                        .unwrap()
                        .into_owned()
                        .to_string_lossy()
                        .to_string();
                    file_list.push(a);
                })
        }
        Ok(file_list)
    }

    fn get_absolute_file_list_of_txz(&self, txz_name: &str) -> Result<Vec<String>> {
        let prefix = self.get_txz_dst(txz_name);
        let relative_files = self.get_relative_file_list_of_txz(txz_name)?;
        Ok(relative_files
            .iter()
            .map(|x| -> PathBuf { prefix.join(x) })
            .map(|y| y.to_str().ok_or(anyhow!("err")).map(|z| z.to_owned()))
            .collect::<Result<Vec<String>>>()?)
    }

    fn unpack_embedded_txz(&self, txz_name: &str, dst_path: PathBuf) -> Result<(), anyhow::Error> {
        debug!(
            "Extracting archive '{}' in folder '{}'",
            txz_name,
            dst_path.display()
        );
        // Loop over ar archive files
        let mut archive = Archive::new(File::open(self.path.clone()).unwrap());
        while let Some(entry_result) = archive.next_entry() {
            let txz_archive = entry_result.unwrap();
            let entry_title = std::str::from_utf8(txz_archive.header().identifier()).unwrap();
            if entry_title != txz_name {
                continue;
            }
            let parent = dst_path.parent().unwrap();
            // Verify that the directory structure exists
            fs::create_dir_all(parent).with_context(|| {
                format!("Make sure the folder '{}' exists", parent.to_str().unwrap(),)
            })?;
            // Unpack the txz archive
            let mut unxz_archive = Vec::new();
            let mut f = std::io::BufReader::new(txz_archive);
            lzma_rs::xz_decompress(&mut f, &mut unxz_archive)?;
            let mut tar_archive = tar::Archive::new(Cursor::new(unxz_archive));
            tar_archive.unpack(dst_path)?;
            return Ok(());
        }
        Ok(())
    }

    pub fn is_installed(&self, db: &Database) -> bool {
        db.is_installed(self)
    }

    pub fn install(&self, force: bool, db: &mut Database, webapp: &mut Webapp) -> Result<()> {
        debug!("Installing rpkg file '{}'...", self.path.display());
        let is_upgrade = self.is_installed(db);
        // Verify webapp compatibility
        if !webapp.version.is_compatible(&self.metadata.version) && !force {
            bail!(
                "This plugin was built for a Rudder '{}', it is incompatible with your current webapp version '{}'.",
                self.metadata.version.rudder_version,
                webapp.version
            )
        }
        // Verify that dependencies are installed
        if let Some(d) = &self.metadata.depends
            && !(force || d.are_installed())
        {
            bail!(
                "Some dependencies are missing, install them before trying to install the plugin."
            )
        }

        if is_upgrade {
            // First uninstall old version, but without running prerm/portrm scripts
            db.uninstall(&self.metadata.name, false, webapp)?;
        }

        // Extract package scripts
        self.unpack_embedded_txz(
            PACKAGE_SCRIPTS_ARCHIVE,
            PathBuf::from(PACKAGES_FOLDER).join(self.metadata.name.clone()),
        )?;
        // Run preinst if any
        let arg = if is_upgrade {
            PackageScriptArg::Upgrade
        } else {
            PackageScriptArg::Install
        };
        self.metadata
            .run_package_script(PackageScript::Preinst, arg)?;

        // Extract archive content
        let keys = self.metadata.content.keys().clone();
        for txz_name in keys {
            self.unpack_embedded_txz(txz_name, self.get_txz_dst(txz_name))?
        }
        // Update the plugin index file to track installed files
        // We need to add the content section to the metadata to do so
        db.insert(
            self.metadata.name.clone(),
            InstalledPlugin {
                files: self.get_archive_installed_files()?,
                metadata: self.metadata.clone(),
            },
        )?;
        // Run postinst if any
        let arg = if self.is_installed(db) {
            PackageScriptArg::Upgrade
        } else {
            PackageScriptArg::Install
        };
        if env::var(DONT_RUN_POSTINST_ENV_VAR).is_ok() {
            debug!(
                "Skipping postinstall scripts as {} environment variable is set",
                DONT_RUN_POSTINST_ENV_VAR
            );
        } else {
            self.metadata
                .run_package_script(PackageScript::Postinst, arg)?;
        }
        // Update the webapp xml file if the plugin contains one or more jar file
        debug!("Enabling the associated jars if any");
        webapp.enable_jars(&self.metadata.jar_files)?;
        // We want to restart even if the enabled plugin list does not change
        // as we have modified the plugin files.
        webapp.trigger_restart();
        info!(
            "Plugin {} was successfully installed",
            self.metadata.short_name()
        );
        Ok(())
    }
}

fn read_metadata(path: &str) -> Result<Metadata> {
    let mut archive = Archive::new(File::open(path).unwrap());
    while let Some(entry_result) = archive.next_entry() {
        let mut entry = entry_result.unwrap();
        let mut buffer = String::new();
        let entry_title = std::str::from_utf8(entry.header().identifier()).unwrap();
        if entry_title == "metadata" {
            let _ = entry.read_to_string(&mut buffer)?;
            let m: Metadata = serde_json::from_str(&buffer)
                .with_context(|| format!("Failed to parse {path} metadata"))?;
            return Ok(m);
        };
    }
    anyhow::bail!("No metadata found in {}", path);
}

#[cfg(test)]
mod tests {
    use std::path::Path;

    use tempfile::tempdir;

    use super::*;

    #[test]
    fn test_read_rpkg_metadata() {
        assert!(read_metadata("./tests/malformed_metadata.rpkg").is_err());
        assert!(read_metadata("./tests/without_metadata.rpkg").is_err());
        read_metadata("./tests/with_metadata.rpkg").unwrap();
    }

    #[test]
    fn test_get_relative_file_list_of_txz() {
        let r = Rpkg::from_path("./tests/archive/rudder-plugin-notify-8.0.0-2.2.rpkg").unwrap();
        assert_eq!(
            r.get_relative_file_list_of_txz("files.txz").unwrap(),
            vec![
                "share/",
                "share/python/",
                "share/python/glpi.py",
                "share/python/notifyd.py"
            ]
        );
        assert_eq!(
            r.get_relative_file_list_of_txz("scripts.txz").unwrap(),
            vec!["postinst"]
        );
    }

    #[test]
    fn test_get_absolute_file_list_of_txz() {
        let r = Rpkg::from_path("./tests/archive/rudder-plugin-notify-8.0.0-2.2.rpkg").unwrap();
        assert_eq!(
            r.get_absolute_file_list_of_txz("files.txz").unwrap(),
            vec![
                "/opt/rudder/share/",
                "/opt/rudder/share/python/",
                "/opt/rudder/share/python/glpi.py",
                "/opt/rudder/share/python/notifyd.py"
            ]
        );
        assert_eq!(
            r.get_absolute_file_list_of_txz("scripts.txz").unwrap(),
            vec!["/var/rudder/packages/rudder-plugin-notify/postinst"]
        );
    }

    #[test]
    fn test_get_archive_installed_files() {
        let r = Rpkg::from_path("./tests/archive/rudder-plugin-notify-8.0.0-2.2.rpkg").unwrap();
        assert_eq!(
            r.get_archive_installed_files().unwrap(),
            vec![
                "/opt/rudder/share/",
                "/opt/rudder/share/python/",
                "/opt/rudder/share/python/glpi.py",
                "/opt/rudder/share/python/notifyd.py"
            ]
        );
    }

    #[test]
    fn test_extract_txz_from_rpkg() {
        let bind;
        let r = Rpkg::from_path("./tests/archive/rudder-plugin-notify-8.0.0-2.2.rpkg").unwrap();
        let expected_dir_content = "./tests/archive/expected_dir_content";
        let d = tempdir().unwrap().keep();
        let effective_target = {
            let real_unpack_target = r.get_txz_dst("files.txz");
            let trimmed = Path::new(&real_unpack_target).strip_prefix("/").unwrap();
            bind = d.join(trimmed);
            bind.to_str().unwrap()
        };
        r.unpack_embedded_txz("files.txz", PathBuf::from(effective_target))
            .unwrap();
        assert!(!dir_diff::is_different(effective_target, expected_dir_content).unwrap());
        fs::remove_dir_all(d).unwrap();
    }

    #[test]
    fn test_extract_broken_archive() {
        let r = Rpkg::from_path("./tests/archive/broken-archive.rpkg").unwrap();
        assert!(
            r.unpack_embedded_txz("files.txz", tempdir().unwrap().keep())
                .is_err()
        );
    }
}
