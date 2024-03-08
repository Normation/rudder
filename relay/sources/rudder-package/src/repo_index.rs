// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

use std::{collections::HashSet, fs, path::Path};

use anyhow::{bail, Context};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use tracing::warn;

use crate::{plugin, versions::RudderVersion};

#[derive(PartialEq, Eq, Debug, Clone)]
pub struct RepoIndex {
    index: Vec<Plugin>,
    pub latest_update: DateTime<Utc>,
}

impl RepoIndex {
    /// Try to read the index. We need to handle the case where the server has not access
    /// to the repository and work offline.
    pub fn from_path(path: &str) -> Result<Option<Self>, anyhow::Error> {
        if Path::new(path).exists() {
            let data = fs::read_to_string(path)?;
            // First read as JSON to avoid failing on invalid entries
            let raw_json: serde_json::Value =
                serde_json::from_str(&data).context("Parsing index from repository")?;
            let Some(plugins) = raw_json.as_array() else {
                bail!("The repository index must be an array")
            };

            let modified = fs::metadata(path)?.modified()?;
            let latest_update: DateTime<Utc> = modified.into();

            let mut index = vec![];
            for entry in plugins {
                match serde_json::from_value(entry.clone()) {
                    Ok(p) => index.push(p),
                    Err(e) => warn!(
                        "Could not parse entry '{}' from repository index: {:?}",
                        serde_json::to_string(entry)?,
                        e
                    ),
                }
            }
            Ok(Some(Self {
                index,
                latest_update,
            }))
        } else {
            Ok(None)
        }
    }

    pub fn inner(&self) -> &[Plugin] {
        self.index.as_slice()
    }

    // What we need to do with the index:
    //
    // * get latest version of a given plugin (for install)
    // * get latest version of all plugins (for list)

    pub fn latest_compatible_plugins(&self, webapp_version: &RudderVersion) -> Vec<&Plugin> {
        let names = self
            .index
            .iter()
            .filter(|p| webapp_version.is_compatible(&p.metadata.version))
            .map(|p| &p.metadata.name)
            .collect::<HashSet<&String>>();
        names
            .into_iter()
            .flat_map(|n| self.latest_compatible_plugin(webapp_version, n))
            .collect()
    }

    pub fn latest_compatible_plugin(
        &self,
        webapp_version: &RudderVersion,
        plugin_name: &str,
    ) -> Option<&Plugin> {
        self.index
            .iter()
            .filter(|p| {
                plugin_name == p.metadata.name && webapp_version.is_compatible(&p.metadata.version)
            })
            .max_by_key(|p| &p.metadata.version)
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
        let expected = vec![
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
      ];
        assert_eq!(expected, index.index);
    }
}
