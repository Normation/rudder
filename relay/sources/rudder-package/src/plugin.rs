// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

use std::{collections::HashMap, fmt::Display, path::Path, process::Command};

use anyhow::bail;
use log::debug;
use serde::{Deserialize, Serialize};

use crate::{
    archive::{self, PackageScript, PackageScriptArg},
    cmd::CmdOutput,
    dependency::Dependencies,
    versions::{self, RudderVersion},
    PACKAGES_FOLDER,
};

pub fn long_names(l: Vec<String>) -> Vec<String> {
    l.into_iter()
        .map(|p| {
            if p.starts_with("rudder-plugin-") {
                p
            } else {
                format!("rudder-plugin-{p}")
            }
        })
        .collect()
}

#[derive(Serialize, Deserialize, PartialEq, Eq, Debug, Clone)]
#[serde(rename_all = "kebab-case")]
pub struct Metadata {
    #[serde(rename = "type")]
    pub package_type: archive::PackageType,
    pub name: String,
    pub version: versions::ArchiveVersion,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    pub build_date: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub depends: Option<Dependencies>,
    pub build_commit: String,
    pub content: HashMap<String, String>,
    #[serde(default)]
    pub jar_files: Vec<String>,
}

/// Not present in metadata but computed from them
///
/// Allows exposing to the user if the plugin will appear in the interface or not.
#[derive(Serialize, Deserialize, PartialEq, Eq, Debug, Copy, Clone)]
#[serde(rename_all = "snake_case")]
pub enum PluginType {
    Web,
    Standalone,
}

impl Display for PluginType {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Self::Web => "web",
            Self::Standalone => "standalone",
        })
    }
}

// Used by the "show" command
impl Display for Metadata {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&format!(
            "Name: {}
Version: {}
Description: {}
Type: {} plugin
Build-date: {}
Build-commit: {}",
            self.name,
            self.version,
            self.description.as_ref().unwrap_or(&"".to_owned()),
            self.plugin_type(),
            self.build_date,
            self.build_commit
        ))?;
        f.write_str("\nJar files:")?;
        if self.jar_files.is_empty() {
            f.write_str(" none")?;
        } else {
            f.write_str("\n")?;
            for j in self.jar_files.iter() {
                write!(f, "  {}", j)?;
            }
        }
        f.write_str("\nContents:\n")?;
        for (a, p) in self.content.iter() {
            writeln!(f, "  {}: {}", a, p)?;
        }
        Ok(())
    }
}

impl Metadata {
    pub fn is_compatible(&self, webapp_version: &RudderVersion) -> bool {
        self.version.rudder_version.is_compatible(webapp_version)
    }

    pub fn plugin_type(&self) -> PluginType {
        if self.jar_files.is_empty() {
            PluginType::Standalone
        } else {
            PluginType::Web
        }
    }

    pub fn short_name(&self) -> &str {
        self.name.strip_prefix("rudder-plugin-").unwrap()
    }

    pub fn run_package_script(
        &self,
        script: PackageScript,
        arg: PackageScriptArg,
    ) -> Result<(), anyhow::Error> {
        debug!(
            "Running package script '{}' with args '{}' for plugin '{}' in version '{}-{}'...",
            script,
            arg,
            self.short_name(),
            self.version.rudder_version,
            self.version.plugin_version
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
