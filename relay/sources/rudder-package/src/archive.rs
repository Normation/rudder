// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

use crate::{
    plugin::Metadata, webapp_xml::WebappXml, PACKAGES_DATABASE_PATH, PACKAGES_FOLDER,
    WEBAPP_XML_PATH,
};
use anyhow::{Context, Ok, Result};
use ar::Archive;
use core::fmt;
use serde::{Deserialize, Serialize};
use std::{
    fs::{self, *},
    io::{Cursor, Read},
    path::Path,
    process::Command,
    str,
};

use crate::database::Database;

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
enum PackageScript {
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
enum PackageScriptArg {
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
    pub path: String,
    pub metadata: Metadata,
}

impl Rpkg {
    fn from_path(path: &str) -> Result<Rpkg> {
        let r = Rpkg {
            path: String::from(path),
            metadata: read_metadata(path).unwrap(),
        };
        Ok(r)
    }

    fn get_txz_dst(&self, txz_name: &str) -> String {
        // Build the destination path
        return self.metadata.content.get(txz_name).unwrap().to_string();
    }

    fn unpack_embedded_txz(&self, txz_name: &str, dst_path: &str) -> Result<(), anyhow::Error> {
        let dst = Path::new(dst_path);
        // Loop over ar archive files
        let mut archive = Archive::new(File::open(self.path.clone()).unwrap());
        while let Some(entry_result) = archive.next_entry() {
            let txz_archive = entry_result.unwrap();
            let entry_title = str::from_utf8(txz_archive.header().identifier()).unwrap();
            if entry_title != txz_name {
                continue;
            }
            let parent = dst.parent().unwrap();
            // Verify that the directory structure exists
            fs::create_dir_all(parent).with_context(|| {
                format!("Make sure the folder '{}' exists", parent.to_str().unwrap(),)
            })?;
            // Unpack the txz archive
            let mut unxz_archive = Vec::new();
            let mut f = std::io::BufReader::new(txz_archive);
            lzma_rs::xz_decompress(&mut f, &mut unxz_archive)?;
            let mut tar_archive = tar::Archive::new(Cursor::new(unxz_archive));
            tar_archive.unpack(dst)?;
            return Ok(());
        }
        Ok(())
    }

    pub fn is_installed(&self) -> Result<bool> {
        let current_database = Database::read(PACKAGES_DATABASE_PATH)?;
        Ok(current_database.is_installed(self.to_owned()))
    }

    pub fn install(&self) -> Result<()> {
        let keys = self.metadata.content.keys().clone();
        // Extract package scripts
        self.unpack_embedded_txz("script.txz", PACKAGES_FOLDER)?;
        // Run preinst if any
        let install_or_upgrade: PackageScriptArg = PackageScriptArg::Install;
        self.run_package_script(PackageScript::Preinst, install_or_upgrade)?;
        // Extract archive content
        for txz_name in keys {
            let dst = self.get_txz_dst(txz_name);
            self.unpack_embedded_txz(txz_name, &dst)?
        }
        // Run postinst if any
        let install_or_upgrade: PackageScriptArg = PackageScriptArg::Install;
        self.run_package_script(PackageScript::Postinst, install_or_upgrade)?;
        // Update the webapp xml file if the plugin contains one or more jar file
        match self.metadata.jar_files.clone() {
            None => (),
            Some(jars) => {
                let w = WebappXml::new(String::from(WEBAPP_XML_PATH));
                for jar_path in jars.into_iter() {
                    w.enable_jar(jar_path)?;
                }
            }
        }
        Ok(())
    }

    fn run_package_script(&self, script: PackageScript, arg: PackageScriptArg) -> Result<()> {
        let package_script_path = Path::new(PACKAGES_FOLDER)
            .join(self.metadata.name.clone())
            .join(script.to_string());
        if !package_script_path.exists() {
            return Ok(());
        }
        let _result = Command::new(package_script_path)
            .arg(arg.to_string())
            .output()?;
        Ok(())
    }
}

fn read_metadata(path: &str) -> Result<Metadata> {
    let mut archive = Archive::new(File::open(path).unwrap());
    while let Some(entry_result) = archive.next_entry() {
        let mut entry = entry_result.unwrap();
        let mut buffer = String::new();
        let entry_title = str::from_utf8(entry.header().identifier()).unwrap();
        if entry_title == "metadata" {
            let _ = entry.read_to_string(&mut buffer)?;
            let m: Metadata = serde_json::from_str(&buffer)
                .with_context(|| format!("Failed to parse {} metadata", path))?;
            return Ok(m);
        };
    }
    anyhow::bail!("No metadata found in {}", path);
}

#[cfg(test)]
mod tests {
    use super::*;

    use tempfile::tempdir;
    extern crate dir_diff;

    #[test]
    fn test_read_rpkg_metadata() {
        assert!(read_metadata("./tests/malformed_metadata.rpkg").is_err());
        assert!(read_metadata("./tests/without_metadata.rpkg").is_err());
        read_metadata("./tests/with_metadata.rpkg").unwrap();
    }

    #[test]
    fn test_extract_txz_from_rpkg() {
        let bind;
        let r = Rpkg::from_path("./tests/archive/rudder-plugin-notify-8.0.0-2.2.rpkg").unwrap();
        let expected_dir_content = "./tests/archive/expected_dir_content";
        let effective_target = {
            let real_unpack_target = r.get_txz_dst("files.txz");
            let trimmed = Path::new(&real_unpack_target).strip_prefix("/").unwrap();
            bind = tempdir().unwrap().into_path().join(trimmed);
            bind.to_str().unwrap()
        };
        r.unpack_embedded_txz("files.txz", effective_target)
            .unwrap();
        assert!(!dir_diff::is_different(effective_target, expected_dir_content).unwrap());
    }
}
