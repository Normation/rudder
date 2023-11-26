// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

use std::{fs, path::Path};

use serde::{Deserialize, Serialize};

use crate::{plugin, versions::RudderVersion};

#[derive(Serialize, Deserialize, PartialEq, Eq, Debug, Clone)]
pub struct RepoIndex(Vec<Plugin>);

impl RepoIndex {
    /// Try to read the index. We need to handle the case where the server has not access
    /// to the repository and work offline.
    pub fn from_path(path: &str) -> Result<Option<Self>, anyhow::Error> {
        if Path::new(path).exists() {
            let data = fs::read_to_string(path)?;
            let index: RepoIndex = serde_json::from_str(&data)?;
            Ok(Some(index))
        } else {
            Ok(None)
        }
    }

    pub fn inner(&self) -> &[Plugin] {
        self.0.as_slice()
    }

    pub fn get_compatible_plugins(&self, webapp_version: &RudderVersion) -> Vec<Plugin> {
        self.clone()
            .0
            .into_iter()
            .filter(|x| webapp_version.is_compatible(&x.metadata.version.rudder_version))
            .collect()
    }

    pub fn get_compatible_plugin(
        &self,
        webapp_version: &RudderVersion,
        plugin_name: &str,
    ) -> Option<Plugin> {
        self.get_compatible_plugins(webapp_version)
            .into_iter()
            // FIXME: could have several plugin version compatible, we need to select the latest
            .find(|x| plugin_name == x.metadata.name)
    }
}

#[derive(Serialize, Deserialize, PartialEq, Eq, Debug, Clone)]
pub struct Plugin {
    pub path: String,

    #[serde(flatten)]
    pub metadata: plugin::Metadata,
}

#[cfg(test)]
mod tests {
    use std::{collections::HashMap, str::FromStr};

    use pretty_assertions::assert_eq;

    use super::*;
    use crate::{archive, versions};

    #[test]
    fn test_plugin_index_parsing() {
        let index: RepoIndex = RepoIndex::from_path("./tests/repo_index.json")
            .unwrap()
            .unwrap();
        let expected = RepoIndex(
      vec![
        Plugin {
          metadata: plugin::Metadata {
            package_type: archive::PackageType::Plugin,
            name: String::from("rudder-plugin-aix"),
            version: versions::ArchiveVersion::from_str("8.0.0~beta2-2.1").unwrap(),
                        description: None,
            build_date: String::from("2023-09-14T14:31:35+00:00"),
            build_commit: String::from("2198ca7c0aa0a4e19f04e0ace099520371641f92"),
            content: HashMap::from([
              (String::from("files.txz"), String::from("/opt/rudder/share/plugins")),
            ]),
            depends: None,
            jar_files: vec![String::from("/opt/rudder/share/plugins/aix/aix.jar")],
          },
          path: String::from("./8.0/aix/release/rudder-plugin-aix-8.0.0~beta2-2.1.rpkg"),
        },
        Plugin {
          metadata: plugin::Metadata {
            package_type: archive::PackageType::Plugin,
            name: String::from("rudder-plugin-aix"),
            version: versions::ArchiveVersion::from_str("8.0.0~rc1-2.1").unwrap(),
                        description: None,
            build_date: String::from("2023-10-13T09:44:54+00:00"),
            build_commit: String::from("cdcf8a4b01124b9b309903cafd95b3a161a9c35c"),
            content: HashMap::from([
              (String::from("files.txz"), String::from("/opt/rudder/share/plugins")),
            ]),
            depends: None,
            jar_files: vec![String::from("/opt/rudder/share/plugins/aix/aix.jar")],
          },
          path: String::from("./8.0/aix/rudder-plugin-aix-8.0.0~rc1-2.1.rpkg/release/rudder-plugin-aix-8.0.0~rc1-2.1.rpkg"),
        },
        Plugin {
          metadata: plugin::Metadata {
            package_type: archive::PackageType::Plugin,
            name: String::from("rudder-plugin-vault"),
            version: versions::ArchiveVersion::from_str("8.0.0~rc1-2.1-nightly").unwrap(),
                        description: None,
            build_date: String::from("2023-10-07T20:38:18+00:00"),
            build_commit: String::from("747126d505b3cac0403014cf35a4caf3a3ec886f"),
            content: HashMap::from([
              (String::from("files.txz"), String::from("/opt/rudder/")),
            ]),
            depends: None,
            jar_files: vec![],
          },
          path: String::from("./8.0/rudder-plugin-vault-8.0.0~rc1-2.1-nightly.rpkg/nightly/rudder-plugin-vault-8.0.0~rc1-2.1-nightly.rpkg"),
        },
      ]);
        assert_eq!(expected, index);
    }
}
