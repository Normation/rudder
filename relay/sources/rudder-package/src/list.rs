// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

use std::collections::HashSet;

use anyhow::Result;

use crate::{cli::Format, database::Database, repo_index::RepoIndex, webapp::Webapp};

/// Content we want to display about each plugin
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ListEntry {
    /// Plugin name ("short")
    name: String,
    /// Full plugin version
    version: String,
    /// None means no higher version is available
    available: Option<String>,
    // None means plugin is not disableable
    enabled: Option<bool>,
}

pub fn list(
    show_all: bool,
    show_only_enabled: bool,
    format: Format,
    db: &Database,
    index: &RepoIndex,
    webapp: &Webapp,
) -> Result<()> {
    let mut plugins: Vec<ListEntry> = vec![];

    // Principles:
    //
    // * By default, display installed plugins.
    // * If an index is available and "all", also add available plugins.
    // * If "enabled", display installed package minus disabled ones.
    let jars = webapp.jars()?;
    let enabled_plugins: HashSet<String> = jars
        .into_iter()
        .flat_map(|j| db.plugin_provides_jar(j))
        .map(|p| p.metadata.name.clone())
        .collect();
    dbg!(&enabled_plugins);

    for p in db.plugins.values() {
        let name = p.metadata.name.strip_prefix("rudder-plugin-").unwrap().to_string();
        let enabled = p
            .metadata
            .jar_files
            .as_ref()
            .map(|_| enabled_plugins.contains(&p.metadata.name));
        let e = ListEntry {
            name,
            version: p.metadata.version.to_string(),
            available: None,
            enabled,
        };
        plugins.push(e);
    }

    if show_all {
        for p in index.inner().iter() {
            //dbg!(&p.metadata.name);
            //dbg!(&p.metadata.version);
        }
    }
    dbg!(plugins);

    Ok(())
}

#[cfg(test)]
mod tests {
    use std::path::PathBuf;

    use crate::{cli::Format, database::Database, repo_index::RepoIndex, webapp::Webapp};

    #[test]
    fn it_lists_plugins() {
        let w = Webapp::new(PathBuf::from("tests/webapp_xml/example.xml"));
        let r = RepoIndex::from_path("./tests/repo_index.json").unwrap();
        let d = Database::read("./tests/database/plugin_database_update_sample.json").unwrap();

        super::list(true, false, Format::Json, &d, &r, &w).unwrap();
    }
}
