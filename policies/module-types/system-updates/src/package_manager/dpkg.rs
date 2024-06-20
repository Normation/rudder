// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use crate::package_manager::{Package, PackageManager};
use anyhow::Result;
use std::env;
use std::process::Command;

pub struct Dpkg {}

impl Dpkg {
    fn new() -> Self {
        env::set_var("DEBIAN_FRONTEND", "noninteractive");
        Self
    }

    fn get_installed(&self) -> Result<Vec<Package>> {
        let output_format = r###"${Package} ${Version} ${Architecture} ${Status}\n"###;
        let c = Command::new("dpkg-query")
            .arg("--showformat")
            .arg(output_format)
            .arg("-W")
            .output();

        let todo = c?;
        Ok(vec![])
    }

    /*
           packages = {}
       for line in output.splitlines():
           parts = line.split(' ')
           state = parts[5]
           if state not in ['installed', 'half-configured', 'half-installed']:
               # consider not installed
               continue
           # Store name with arch to allow fast indexing when comparing versions
           # we can have several packages with the same name and different arches
           index = parts[0] + '.' + parts[2]
           packages[index] = {'version': parts[1]}
           if state != 'installed':
               packages[index]['error'] = state
       return packages
    */

    // unattended-upgrades 1.11.2 all install ok installed
    fn parse_installed(&self, s: &str) -> Result<Vec<Package>> {
        let mut packages = vec![];

        for l in s.lines() {
            let parts: Vec<&str> = l.split(' ').collect();
            let state = parts[5];

            if !["installed", "hash-configured", "half-installed"].contains(&state) {
                // consider not installed
                continue;
            }

            // FIXME: Store name with arch to allow fast indexing, when comparing versions,
            // we can have several packages with the same name and different arches

            let p = Package {
                name: parts[0].to_string(),
                version: parts[1].to_string(),
                architecture: parts[2].to_string(),
                // TODO?
                from: "".to_string(),
                source: PackageManager::Dpkg,
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
        let output = "python3-yaml 3.13-2 amd64 install ok installed
python3.5-minimal 3.5.3-1+deb9u1 amd64 deinstall ok config-files
python3.7 3.7.3-2+deb10u3 amd64 install ok installed";

        let reference = vec![
            Package {
                name: "python3-yaml".to_string(),
                version: "3.13-2".to_string(),
                architecture: "amd64".to_string(),
                from: "".to_string(),
                source: PackageManager::Dpkg,
            },
            Package {
                name: "python3.7".to_string(),
                version: "3.7.3-2+deb10u3".to_string(),
                architecture: "amd64".to_string(),
                from: "".to_string(),
                source: PackageManager::Dpkg,
            },
        ];

        let d = Dpkg::new();
        assert_eq!(d.parse_installed(output).unwrap(), reference);
    }
}
