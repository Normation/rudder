// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

use std::{collections::HashMap, fmt::Display, io::BufWriter, path::Path, process::Command};

use anyhow::bail;
use serde::{Deserialize, Serialize};
use std::fmt;
use std::path::PathBuf;
use tracing::debug;

use crate::{
    archive::{self, PackageScript, PackageScriptArg},
    cmd::CmdOutput,
    dependency::Dependencies,
    versions,
};

const PACKAGES_FOLDER: &str = "/var/rudder/packages";
const PACKAGE_CONTENT_DEFAULT_FOLDER: &str = "/opt/rudder/share/plugins";

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

// A package name must only use authorized chars
#[derive(Clone, Debug, Hash, PartialEq, Eq, Serialize, Deserialize)]
#[serde(try_from = "String")]
pub struct SafePackageName(String);

impl PartialEq<&str> for SafePackageName {
    fn eq(&self, other: &&str) -> bool {
        &self.0 == other
    }
}
impl PartialEq<String> for SafePackageName {
    fn eq(&self, other: &String) -> bool {
        &self.0 == other
    }
}

impl fmt::Display for SafePackageName {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl TryFrom<String> for SafePackageName {
    type Error = anyhow::Error;

    fn try_from(s: String) -> Result<Self, Self::Error> {
        SafePackageName::try_from(s.as_str())
    }
}

impl TryFrom<&str> for SafePackageName {
    type Error = anyhow::Error;

    fn try_from(s: &str) -> Result<Self, Self::Error> {
        fn valid_char(c: char) -> bool {
            let v = ['_', '-'];
            c.is_ascii_alphanumeric() || v.contains(&c)
        }
        if s.is_empty() || short_name(s).is_empty() || !s.chars().all(valid_char) {
            bail!(
                "Invalid package name: '{}', only ASCII alphanumerics, '_' and '-' are allowed",
                s
            )
        }
        Ok(Self(s.to_string()))
    }
}

#[derive(Serialize, Deserialize, PartialEq, Eq, Debug, Clone)]
#[serde(rename_all = "kebab-case")]
pub struct Metadata {
    #[serde(rename = "type")]
    pub package_type: archive::PackageType,
    pub name: SafePackageName,
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
    /// Does the plugin require a valid license.
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
    pub fn scripts_dir(&self) -> PathBuf {
        Path::new(PACKAGES_FOLDER).join(&self.name.0)
    }

    pub fn content_dir(&self) -> PathBuf {
        Path::new(PACKAGE_CONTENT_DEFAULT_FOLDER).join(self.short_name())
    }

    pub fn is_webapp(&self) -> bool {
        !self.jar_files.is_empty()
    }

    pub fn short_name(&self) -> &str {
        short_name(&self.name.0)
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
        let package_script_path = self.scripts_dir().join(script.to_string());
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

    use super::*;
    use rstest::rstest;
    use std::path::Path;
    use std::str::FromStr;

    #[rstest]
    #[case("rudder-plugin-system-updates")]
    #[case("rudder-plugin-system-aix")]
    #[case("my_plugin-name")]
    #[case("-------my-plugin")]
    fn package_name_accepts_good_names(#[case] s: &str) {
        assert!(SafePackageName::try_from(s).is_ok());
        let json = format!(r#""{}""#, s);
        serde_json::from_str::<SafePackageName>(&json).unwrap();
    }

    #[rstest]
    #[case("")]
    #[case("rudder-plugin-")]
    #[case("rudder-plugin-..")]
    #[case("rudder-plugin-system~updates")]
    #[case("rudder-plugin-system/updates")]
    #[case("rudder-plugin-🐒-system")]
    #[case("/etc/")]
    #[case("../etc/")]
    #[case("foo\\bar")]
    #[case("foo\\-bar")]
    fn package_name_rejects_bad_names(#[case] s: &str) {
        assert!(SafePackageName::try_from(s).is_err());
        let json = format!(r#""{}""#, s);
        assert!(serde_json::from_str::<SafePackageName>(&json).is_err())
    }

    #[test]
    fn safe_package_name_path() {
        let m = Metadata {
            package_type: archive::PackageType::Plugin,
            name: SafePackageName::try_from("rudder-plugin-dsc").unwrap(),
            version: versions::ArchiveVersion::from_str("8.0.0~beta2-2.1").unwrap(),
            description: None,
            build_date: String::from("2023-09-14T14:31:35+00:00"),
            build_commit: String::from("2198ca7c0aa0a4e19f04e0ace099520371641f92"),
            content: HashMap::from([(
                String::from("files.txz"),
                String::from("/opt/rudder/share/plugins"),
            )]),
            depends: None,
            jar_files: vec![String::from("/opt/rudder/share/plugins/aix/aix.jar")],
            requires_license: false,
        };
        assert_eq!(
            m.scripts_dir(),
            Path::new("/var/rudder/packages/rudder-plugin-dsc")
        );
        assert_eq!(m.content_dir(), Path::new("/opt/rudder/share/plugins/dsc"));
    }
}
