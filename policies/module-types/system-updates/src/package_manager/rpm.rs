// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use std::{collections::HashMap, process::Command};

use crate::output::{CommandBehavior, CommandCapture};
use crate::{
    output::ResultOutput,
    package_manager::{PackageId, PackageInfo, PackageList, PackageManager},
};
use anyhow::Result;

pub struct RpmPackageManager {}

impl RpmPackageManager {
    pub fn new() -> Self {
        Self {}
    }

    pub fn installed(&self) -> ResultOutput<PackageList> {
        let output_format = r###"%{name} %{epochnum}:%{version}-%{release} %{arch}\n"###;
        let mut c = Command::new("rpm");
        c.arg("-qa").arg("--qf").arg(output_format);
        let res =
            ResultOutput::command(c, CommandBehavior::FailOnErrorCode, CommandCapture::Stderr);
        let (r, o, e) = (res.inner, res.stdout, res.stderr);
        let res = match r {
            Ok(o) => {
                let out = String::from_utf8_lossy(&o.stdout);
                match self.parse_installed(out.as_ref()) {
                    Ok(packages) => Ok(packages),
                    Err(e) => Err(e.context("Parsing rpm output")),
                }
            }
            Err(e) => Err(e.context("Running rpm command")),
        };
        ResultOutput::new_output(res, o, e)
    }

    fn parse_installed(&self, s: &str) -> Result<PackageList> {
        let mut packages = HashMap::new();

        for l in s.lines() {
            let parts: Vec<&str> = l.split(' ').collect();
            let version_parts: Vec<&str> = parts[1].split(':').collect();
            let version = if ["", "0"].contains(&version_parts[0]) {
                // Remove default epoch
                version_parts[1]
            } else {
                parts[1]
            };

            let p = PackageId::new(parts[0].to_string(), parts[2].to_string());
            let i = PackageInfo {
                version: version.to_string(),
                // TODO?
                from: "".to_string(),
                source: PackageManager::Rpm,
                details: None,
            };
            packages.insert(p, i);
        }

        Ok(PackageList::new(packages))
    }
}

#[cfg(test)]
mod tests {
    use pretty_assertions::assert_eq;

    use super::*;

    #[test]
    fn it_parses_installed_list() {
        let output = "sushi 2:41.2-2.fc36 x86_64
gtksourceview5 5.4.2-1.fc36 x86_64
gnome-software 42.2-4.fc36 x86_64
google-chrome-stable 0:103.0.5060.53-1 x86_64";

        let mut l = HashMap::new();
        l.insert(
            PackageId::new("sushi".to_string(), "x86_64".to_string()),
            PackageInfo {
                version: "2:41.2-2.fc36".to_string(),
                from: "".to_string(),
                source: PackageManager::Rpm,
                details: None,
            },
        );
        l.insert(
            PackageId::new("gtksourceview5".to_string(), "x86_64".to_string()),
            PackageInfo {
                version: "5.4.2-1.fc36".to_string(),
                from: "".to_string(),
                source: PackageManager::Rpm,
                details: None,
            },
        );
        l.insert(
            PackageId::new("gnome-software".to_string(), "x86_64".to_string()),
            PackageInfo {
                version: "42.2-4.fc36".to_string(),
                from: "".to_string(),
                source: PackageManager::Rpm,
                details: None,
            },
        );
        l.insert(
            PackageId::new("google-chrome-stable".to_string(), "x86_64".to_string()),
            PackageInfo {
                version: "103.0.5060.53-1".to_string(),
                from: "".to_string(),
                source: PackageManager::Rpm,
                details: None,
            },
        );

        let rpm = RpmPackageManager::new();

        assert_eq!(rpm.parse_installed(output).unwrap().inner, l);
    }
}
