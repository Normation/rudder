// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use crate::package_manager::{Package, PackageManager};
use anyhow::Result;
use std::process::Command;

pub struct Rpm {}

impl Rpm {
    fn get_installed() -> Result<Vec<Package>> {
        let output_format = r###"%{name} %{epochnum}:%{version}-%{release} %{arch}\n"###;
        let c = Command::new("rpm")
            .arg("-qa")
            .arg("--qf")
            .arg(output_format)
            .output();

        let todo = c?;
        Ok(vec![])
    }

    fn parse_installed(s: &str) -> Result<Vec<Package>> {
        let mut packages = vec![];

        for l in s.lines() {
            let parts: Vec<&str> = l.split(' ').collect();
            let version_parts: Vec<&str> = parts[1].split(':').collect();
            let version = if ["", "0"].contains(&version_parts[0]) {
                // Remove default epoch
                version_parts[1]
            } else {
                parts[1]
            };

            // FIXME: Store name with arch to allow fast indexing, when comparing versions,
            // we can have several packages with the same name and different arches

            let p = Package {
                name: parts[0].to_string(),
                version: version.to_string(),
                architecture: parts[2].to_string(),
                // TODO?
                from: "".to_string(),
                source: PackageManager::Rpm,
            };
            packages.push(p);
        }

        Ok(packages)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use pretty_assertions::assert_eq;

    #[test]
    fn it_parses_installed_list() {
        let output = "sushi 2:41.2-2.fc36 x86_64
mesa-vulkan-drivers 22.1.2-1.fc36 x86_64
gtksourceview5 5.4.2-1.fc36 x86_64
gnome-software 42.2-4.fc36 x86_64
google-chrome-stable 0:103.0.5060.53-1 x86_64";

        let reference = vec![
            Package {
                name: "sushi".to_string(),
                version: "2:41.2-2.fc36".to_string(),
                architecture: "x86_64".to_string(),
                from: "".to_string(),
                source: PackageManager::Rpm,
            },
            Package {
                name: "mesa-vulkan-drivers".to_string(),
                version: "22.1.2-1.fc36".to_string(),
                architecture: "x86_64".to_string(),
                from: "".to_string(),
                source: PackageManager::Rpm,
            },
            Package {
                name: "gtksourceview5".to_string(),
                version: "5.4.2-1.fc36".to_string(),
                architecture: "x86_64".to_string(),
                from: "".to_string(),
                source: PackageManager::Rpm,
            },
            Package {
                name: "gnome-software".to_string(),
                version: "42.2-4.fc36".to_string(),
                architecture: "x86_64".to_string(),
                from: "".to_string(),
                source: PackageManager::Rpm,
            },
            Package {
                name: "google-chrome-stable".to_string(),
                version: "103.0.5060.53-1".to_string(),
                architecture: "x86_64".to_string(),
                from: "".to_string(),
                source: PackageManager::Rpm,
            },
        ];

        assert_eq!(Rpm::parse_installed(output).unwrap(), reference);
    }
}
