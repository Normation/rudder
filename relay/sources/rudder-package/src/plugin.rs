// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

use std::{collections::HashMap, path::Path, process::Command};

use anyhow::bail;
use log::debug;
use serde::{Deserialize, Serialize};

use crate::{
    archive::{self, PackageScript, PackageScriptArg},
    cmd::CmdOutput,
    dependency::Dependencies,
    versions, PACKAGES_FOLDER,
};

#[derive(Serialize, Deserialize, PartialEq, Eq, Debug, Clone)]
pub struct Metadata {
    #[serde(rename = "type")]
    pub plugin_type: archive::PackageType,
    pub name: String,
    pub version: versions::ArchiveVersion,
    #[serde(rename(serialize = "build-date", deserialize = "build-date"))]
    pub build_date: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub depends: Option<Dependencies>,
    #[serde(rename(serialize = "build-commit", deserialize = "build-commit"))]
    pub build_commit: String,
    pub content: HashMap<String, String>,
    #[serde(rename(serialize = "jar-files", deserialize = "jar-files"))]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub jar_files: Option<Vec<String>>,
}

impl Metadata {
    pub fn is_compatible(&self, webapp_version: &str) -> bool {
        self.version.rudder_version.is_compatible(webapp_version)
    }

    pub fn run_package_script(
        &self,
        script: PackageScript,
        arg: PackageScriptArg,
    ) -> Result<(), anyhow::Error> {
        debug!(
            "Running package script '{}' with args '{}' for plugin '{}' in version '{}-{}'...",
            script, arg, self.name, self.version.rudder_version, self.version.plugin_version
        );
        let package_script_path = Path::new(PACKAGES_FOLDER)
            .join(self.name.clone())
            .join(script.to_string());
        if !package_script_path.exists() {
            debug!("Skipping as the script does not exist.");
            return Ok(());
        }
        let mut binding = Command::new(package_script_path);
        let cmd = binding.arg(arg.to_string());
        let r = match CmdOutput::new(cmd) {
            Ok(a) => a,
            Err(e) => {
                bail!("Could not execute package script '{}'`n{}", script, e);
            }
        };
        if !r.output.status.success() {
            debug!("Package script execution return unexpected exit code.");
        }
        Ok(())
    }
}