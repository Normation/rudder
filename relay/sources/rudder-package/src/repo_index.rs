// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

use crate::plugin;
use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, PartialEq, Eq, Debug, Clone)]
pub struct RepoIndex(Vec<Plugin>);

#[derive(Serialize, Deserialize, PartialEq, Eq, Debug, Clone)]
pub struct Plugin {
    path: String,

    #[serde(flatten)]
    metadata: plugin::Metadata,
}

#[cfg(test)]
mod tests {
    use crate::archive;

    use super::*;
    use pretty_assertions::assert_eq;
    use std::{collections::HashMap, fs};

    #[test]
    fn test_plugin_index_parsing() {
        let data = fs::read_to_string("./tests/repo_index.json")
            .expect("Unable to parse file './tests/repo_index.json'");
        let index: RepoIndex = serde_json::from_str(&data).unwrap();
        let expected = RepoIndex(
      vec![
        Plugin {
          metadata: plugin::Metadata {
            plugin_type: archive::PackageType::Plugin,
            name: String::from("rudder-plugin-aix"),
            version: String::from("8.0.0~beta2-2.1"),
            build_date: String::from("2023-09-14T14:31:35+00:00"),
            build_commit: String::from("2198ca7c0aa0a4e19f04e0ace099520371641f92"),
            content: HashMap::from([
              (String::from("files.txz"), String::from("/opt/rudder/share/plugins")),
            ]),
            depends: None,
            jar_files: Some(vec![String::from("/opt/rudder/share/plugins/aix/aix.jar")]),
          },
          path: String::from("./8.0/aix/release/rudder-plugin-aix-8.0.0~beta2-2.1.rpkg"),
        },
        Plugin {
          metadata: plugin::Metadata {
            plugin_type: archive::PackageType::Plugin,
            name: String::from("rudder-plugin-aix"),
            version: String::from("8.0.0~rc1-2.1"),
            build_date: String::from("2023-10-13T09:44:54+00:00"),
            build_commit: String::from("cdcf8a4b01124b9b309903cafd95b3a161a9c35c"),
            content: HashMap::from([
              (String::from("files.txz"), String::from("/opt/rudder/share/plugins")),
            ]),
            depends: None,
            jar_files: Some(vec![String::from("/opt/rudder/share/plugins/aix/aix.jar")]),
          },
          path: String::from("./8.0/aix/rudder-plugin-aix-8.0.0~rc1-2.1.rpkg/release/rudder-plugin-aix-8.0.0~rc1-2.1.rpkg"),
        },
        Plugin {
          metadata: plugin::Metadata {
            plugin_type: archive::PackageType::Plugin,
            name: String::from("rudder-plugin-vault"),
            version: String::from("8.0.0~rc1-2.1-nightly"),
            build_date: String::from("2023-10-07T20:38:18+00:00"),
            build_commit: String::from("747126d505b3cac0403014cf35a4caf3a3ec886f"),
            content: HashMap::from([
              (String::from("files.txz"), String::from("/opt/rudder/")),
            ]),
            depends: None,
            jar_files: None,
          },
          path: String::from("./8.0/rudder-plugin-vault-8.0.0~rc1-2.1-nightly.rpkg/nightly/rudder-plugin-vault-8.0.0~rc1-2.1-nightly.rpkg"),
        },
      ]);
        assert_eq!(expected, index);
    }
}
