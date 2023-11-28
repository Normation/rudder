// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

use std::collections::HashSet;

use anyhow::Result;
use serde::Serialize;

use crate::{
    cli::Format,
    database::Database,
    license::{License, Licenses},
    plugin::PluginType,
    repo_index::RepoIndex,
    webapp::Webapp,
};

pub struct ListOutput {
    inner: Vec<ListEntry>,
}

/// Content we want to display about each plugin
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
struct ListEntry {
    /// Plugin name ("short")
    name: String,
    /// Full plugin version
    #[serde(skip_serializing_if = "Option::is_none")]
    version: Option<String>,
    /// None means no higher version is available
    #[serde(skip_serializing_if = "Option::is_none")]
    latest_version: Option<String>,
    installed: bool,
    /// Only true for enabled web plugins
    enabled: bool,
    #[serde(rename = "type")]
    plugin_type: PluginType,
    #[serde(skip_serializing_if = "Option::is_none")]
    description: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    license: Option<License>,
}

impl ListOutput {
    fn human_table(self) -> Result<()> {
        use cli_table::{print_stdout, Cell, Style, Table};

        let table = self
            .inner
            .into_iter()
            .map(|e| {
                vec![
                    e.name.cell(),
                    e.version.as_ref().unwrap_or(&"".to_string()).cell(),
                    if e.latest_version == e.version {
                        "".to_string()
                    } else {
                        e.latest_version.unwrap_or("".to_string())
                    }
                    .cell(),
                    e.plugin_type.cell(),
                    match (e.installed, e.enabled, e.plugin_type) {
                        (false, _, _) => "",
                        (true, true, _) => "enabled",
                        (true, false, PluginType::Web) => "disabled",
                        (true, false, PluginType::Standalone) => "",
                    }
                    .cell(),
                    e.license
                        .map(|l| l.end_date)
                        .unwrap_or("".to_string())
                        .cell(),
                    e.description.unwrap_or("".to_string()).cell(),
                ]
            })
            .table()
            .title(vec![
                "Name".cell().bold(true),
                "Installed".cell().bold(true),
                "Latest".cell().bold(true),
                "Type".cell().bold(true),
                "Status".cell().bold(true),
                "License valid until".cell().bold(true),
                "Description".cell().bold(true),
            ]);
        print_stdout(table)?;
        Ok(())
    }

    fn json(&self) -> Result<()> {
        let out = serde_json::to_string(&self.inner)?;
        println!("{}", out);
        Ok(())
    }

    pub fn new(
        show_all: bool,
        show_only_enabled: bool,
        licenses: &Licenses,
        db: &Database,
        index: Option<&RepoIndex>,
        webapp: &Webapp,
    ) -> Result<Self> {
        let mut plugins: Vec<ListEntry> = vec![];
        // Principles:
        //
        // * By default, display installed plugins.
        // * If an index is available and "all", also add available plugins.
        // * If "enabled", display installed package minus disabled ones.
        let jars = webapp.jars()?;
        let installed_plugins: Vec<&String> = db.plugins.keys().collect();
        let enabled_plugins: HashSet<String> = jars
            .into_iter()
            .flat_map(|j| db.plugin_provides_jar(&j))
            .map(|p| p.metadata.name.clone())
            .collect();
        let latest = index.map(|i| i.latest_compatible_plugins(&webapp.version));

        for p in db.plugins.values() {
            let name = p.metadata.short_name().to_string();
            let enabled = enabled_plugins.contains(&p.metadata.name);
            let latest_version = index
                .and_then(|i| i.latest_compatible_plugin(&webapp.version, &p.metadata.name))
                .map(|p| p.metadata.version.to_string());

            let e = ListEntry {
                name,
                version: Some(p.metadata.version.to_string()),
                latest_version,
                plugin_type: p.metadata.plugin_type(),
                enabled,
                installed: true,
                description: p.metadata.description.clone(),
                license: licenses.inner.get(&p.metadata.name).cloned(),
            };
            if !show_only_enabled || enabled {
                // Standalone plugins are always considered enabled
                // (even if some might not be: disabled systemd service for notify, etc.)
                plugins.push(e);
            }
        }

        if show_all {
            if let Some(available) = latest {
                for p in available {
                    if !installed_plugins.contains(&&p.metadata.name) {
                        let name = p.metadata.short_name().to_string();
                        let e = ListEntry {
                            name,
                            version: None,
                            latest_version: Some(p.metadata.version.to_string()),
                            plugin_type: p.metadata.plugin_type(),
                            installed: false,
                            enabled: false,
                            description: p.metadata.description.clone(),
                            license: licenses.inner.get(&p.metadata.name).cloned(),
                        };
                        plugins.push(e);
                    }
                }
            }
        }

        // Sort by name alphabetical order
        plugins.sort_by_key(|e| e.name.clone());
        Ok(Self { inner: plugins })
    }

    pub fn display(self, format: Format) -> Result<()> {
        match format {
            Format::Json => self.json()?,
            Format::Human => self.human_table()?,
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use std::path::{Path, PathBuf};

    use super::ListOutput;
    use crate::{
        cli::Format, database::Database, license::Licenses, repo_index::RepoIndex,
        versions::RudderVersion, webapp::Webapp,
    };

    #[test]
    fn it_lists_plugins() {
        let w = Webapp::new(
            PathBuf::from("tests/webapp_xml/example.xml"),
            RudderVersion::from_path("./tests/versions/rudder-server-version").unwrap(),
        );
        let r = RepoIndex::from_path("./tests/repo_index.json")
            .unwrap()
            .unwrap();
        let d = Database::read(Path::new(
            "./tests/database/plugin_database_update_sample.json",
        ))
        .unwrap();
        let l = Licenses::from_path(Path::new("tests/licenses")).unwrap();
        let out = ListOutput::new(true, false, &l, &d, Some(&r), &w).unwrap();
        out.display(Format::Human).unwrap();
    }
}
