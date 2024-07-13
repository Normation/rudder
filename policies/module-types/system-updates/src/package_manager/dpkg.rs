// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use std::{collections::HashMap, env, process::Command};

use anyhow::Result;

use crate::package_manager::{PackageId, PackageInfo, PackageList, PackageManager};

pub struct DpkgPackageManager {}

// FIXME ADD arbitrary options

impl DpkgPackageManager {
    pub fn new() -> Self {
        env::set_var("DEBIAN_FRONTEND", "noninteractive");
        Self {}
    }

    pub fn installed(&self) -> Result<PackageList> {
        let output_format = r###"${Package} ${Version} ${Architecture} ${Status}\n"###;
        let c = Command::new("dpkg-query")
            .arg("--showformat")
            .arg(output_format)
            .arg("-W")
            .output()?;

        let out = String::from_utf8_lossy(&c.stdout);
        let packages = self.parse_installed(out.as_ref())?;
        Ok(packages)
    }

    // unattended-upgrades 1.11.2 all install ok installed
    fn parse_installed(&self, s: &str) -> Result<PackageList> {
        let mut packages = HashMap::new();

        for l in s.lines() {
            let parts: Vec<&str> = l.split(' ').collect();
            let state = parts[5];

            if !["installed", "hash-configured", "half-installed"].contains(&state) {
                // consider not installed
                continue;
            }

            let p = PackageId::new(parts[0].to_string(), parts[2].to_string());
            let i = PackageInfo {
                version: parts[1].to_string(),
                // FIXME add
                from: "".to_string(),
                source: PackageManager::Dpkg,
            };
            packages.insert(p, i);
        }

        Ok(PackageList { inner: packages })
    }
}

#[cfg(test)]
mod tests {
    use pretty_assertions::assert_eq;

    use super::*;

    #[test]
    fn it_parses_installed_list() {
        let output = "python3-yaml 3.13-2 amd64 install ok installed
python3.5-minimal 3.5.3-1+deb9u1 amd64 deinstall ok config-files
python3.7 3.7.3-2+deb10u3 amd64 install ok installed";

        let mut l = HashMap::new();
        l.insert(
            PackageId::new("python3-yaml".to_string(), "amd64".to_string()),
            PackageInfo {
                version: "3.13-2".to_string(),
                from: "".to_string(),
                source: PackageManager::Dpkg,
            },
        );
        l.insert(
            PackageId::new("python3.7".to_string(), "amd64".to_string()),
            PackageInfo {
                version: "3.7.3-2+deb10u3".to_string(),
                from: "".to_string(),
                source: PackageManager::Dpkg,
            },
        );

        let d = DpkgPackageManager::new();
        assert_eq!(d.parse_installed(output).unwrap().inner, l);
    }
}
