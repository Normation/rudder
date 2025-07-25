// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

use std::{collections::HashMap, fmt::Display, io::BufWriter, path::Path, process::Command};

use anyhow::bail;
use serde::{Deserialize, Serialize};
use tracing::debug;

use crate::{
    PACKAGES_FOLDER,
    archive::{self, PackageScript, PackageScriptArg},
    cmd::CmdOutput,
    dependency::Dependencies,
    versions,
};

pub fn long_names(l: Vec<String>) -> Vec<String> {
    l.into_iter()
        .map(|n| {
            if ["rudder-plugin-", "/", "."]
                .iter()
                .any(|p| n.starts_with(p))
            {
                n
            } else {
                format!("rudder-plugin-{n}")
            }
        })
        .collect()
}

pub fn short_name(p: &str) -> &str {
    p.strip_prefix("rudder-plugin-").unwrap_or(p)
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
    #[serde(default)]
    /// Does the plugin reauire a valid license.
    ///
    /// Default is false.
    pub requires_license: bool,
}

// Used by the "show" command
impl Display for Metadata {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&format!(
            "Name: {}
Version: {}
Description: {}
Type: plugin {}
Build-date: {}
Build-commit: {}",
            self.short_name(),
            self.version,
            self.description.as_ref().unwrap_or(&"".to_owned()),
            if self.is_webapp() { "(webapp)" } else { "" },
            self.build_date,
            self.build_commit
        ))?;
        f.write_str("\nJar files:")?;
        if self.jar_files.is_empty() {
            f.write_str(" none")?;
        } else {
            f.write_str("\n")?;
            for j in self.jar_files.iter() {
                write!(f, "  {j}")?;
            }
        }
        f.write_str("\nContents:\n")?;
        for (a, p) in self.content.iter() {
            writeln!(f, "  {a}: {p}")?;
        }
        Ok(())
    }
}

impl Metadata {
    pub fn is_webapp(&self) -> bool {
        !self.jar_files.is_empty()
    }

    pub fn short_name(&self) -> &str {
        short_name(&self.name)
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
        let mut binding = Command::new(package_script_path.clone());
        let cmd = binding.arg(arg.to_string());
        let r = match CmdOutput::new(cmd) {
            Ok(a) => a,
            Err(e) => {
                bail!("Could not execute package script '{}'`n{}", script, e);
            }
        };
        let mut package_script_logfile = package_script_path;
        package_script_logfile.set_extension("log");
        let file = std::fs::OpenOptions::new()
            .write(true)
            .create(true)
            .truncate(true)
            .open(package_script_logfile)?;
        let mut writer = BufWriter::new(file);
        let _ = serde_json::to_writer_pretty(&mut writer, &r);
        if !r.output.status.success() {
            bail!(
                "Package script '{}' for plugin '{}' returned '{}'",
                script,
                self.short_name(),
                r.output.status
            );
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use crate::archive::PackageType;
    use crate::plugin::Metadata;
    use crate::versions::{ArchiveVersion, PluginVersion, RudderVersion, RudderVersionMode};
    use std::collections::HashMap;

    #[test]
    fn test_deserialize_metadata() {
        let raw = r#"
{
  "type": "plugin",
  "name": "rudder-plugin-benchmark-cis-ubuntu-20-04",
  "description": "Provides a security-benchmark implementation for the CIS Ubuntu 20 benchmark",
  "version": "8.3.0~beta2-0.10-nightly",
  "build-date": "2025-04-07T16:12:45+02:00",
  "build-commit": "90cfeb484f6212f9d19ec72bbdc85f1b34547c25",
  "jar-files": [],
  "content": {
    "files.txz": "/opt/rudder/share/plugins"
  },
  "requires-license": false,
  "extra_field": true
}
        "#;
        let metadata: Metadata = serde_json::from_str(raw).unwrap();
        assert_eq!(
            metadata,
            Metadata {
                package_type: PackageType::Plugin,
                name: "rudder-plugin-benchmark-cis-ubuntu-20-04".to_string(),
                version: ArchiveVersion {
                    plugin_version: PluginVersion {
                        major: 0,
                        minor: 10,
                        nightly: true,
                    },
                    rudder_version: RudderVersion {
                        major: 8,
                        minor: 3,
                        patch: 0,
                        mode: RudderVersionMode::Beta { version: 2 },
                        nightly: None,
                    }
                },
                description: Some(
                    "Provides a security-benchmark implementation for the CIS Ubuntu 20 benchmark"
                        .to_string()
                ),
                build_date: "2025-04-07T16:12:45+02:00".to_string(),
                depends: None,
                build_commit: "90cfeb484f6212f9d19ec72bbdc85f1b34547c25".to_string(),
                content: HashMap::from([(
                    "files.txz".to_string(),
                    "/opt/rudder/share/plugins".to_string()
                )]),
                jar_files: vec![],
                requires_license: false,
            }
        )
    }
}
