// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

use std::collections::HashSet;

use anyhow::Result;
use chrono::Utc;
use cli_table::format::{HorizontalLine, Separator, VerticalLine};
use serde::Serialize;
use tracing::warn;

use crate::{
    cli::Format,
    database::Database,
    license::{License, Licenses},
    repo_index::RepoIndex,
    webapp::Webapp,
};

#[derive(Clone)]
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
    webapp_plugin: bool,
    requires_license: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    description: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    license: Option<License>,
}

impl ListOutput {
    fn human_table(self) -> Result<()> {
        use cli_table::{Cell, Style, Table, print_stdout};
        let now = Utc::now();

        let table = self
            .inner
            .into_iter()
            .map(|e| {
                vec![
                    e.name.cell(),
                    e.version.as_ref().unwrap_or(&"".to_string()).cell(),
                    if e.installed {
                        if e.latest_version == e.version {
                            "up-to-date".to_string()
                        } else {
                            e.latest_version.unwrap_or("none".to_string())
                        }
                    } else {
                        // No need to show version if not installed
                        "".to_string()
                    }
                    .cell(),
                    match (e.installed, e.enabled, e.webapp_plugin) {
                        (false, _, true) => "yes",
                        (true, true, true) => "yes: enabled",
                        (true, false, true) => "yes: disabled",
                        _ => "",
                    }
                    .cell(),
                    e.license
                        .map(|l| l.end_date)
                        .map(|e| {
                            if e > now {
                                format!("until {}", e.format("%Y/%m/%d"))
                            } else {
                                "expired".to_string()
                            }
                        })
                        .unwrap_or(if e.requires_license {
                            if e.installed {
                                "missing".to_string()
                            } else {
                                "no available".to_string()
                            }
                        } else {
                            "".to_string()
                        })
                        .cell(),
                    e.description.unwrap_or("".to_string()).cell(),
                ]
            })
            .table()
            .separator(
                Separator::builder()
                    .column(Some(VerticalLine::new('|')))
                    .title(Some(HorizontalLine::new('+', '+', '+', '-')))
                    .build(),
            )
            .title(vec![
                "Name".cell().bold(true),
                "Installed".cell().bold(true),
                "Latest".cell().bold(true),
                "Web plugin".cell().bold(true),
                "License".cell().bold(true),
                "Description".cell().bold(true),
            ]);
        print_stdout(table)?;
        Ok(())
    }

    fn json(&self) -> Result<String> {
        let out = serde_json::to_string(&self.inner)?;
        Ok(out)
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
        if index.is_none() {
            warn!("No repository index found. Try to update it using 'rudder package update'")
        }
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
                webapp_plugin: p.metadata.is_webapp(),
                requires_license: p.metadata.requires_license,
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

        if show_all && let Some(available) = latest {
            for p in available {
                if !installed_plugins.contains(&&p.metadata.name) {
                    let name = p.metadata.short_name().to_string();
                    let e = ListEntry {
                        name,
                        version: None,
                        latest_version: Some(p.metadata.version.to_string()),
                        webapp_plugin: p.metadata.is_webapp(),
                        requires_license: p.metadata.requires_license,
                        installed: false,
                        enabled: false,
                        description: p.metadata.description.clone(),
                        license: licenses.inner.get(&p.metadata.name).cloned(),
                    };
                    plugins.push(e);
                }
            }
        }

        // Sort by name alphabetical order
        plugins.sort_by_key(|e| e.name.clone());
        Ok(Self { inner: plugins })
    }

    pub fn display(self, format: Format) -> Result<()> {
        match format {
            Format::Json => println!("{}", self.json()?),
            Format::Human => self.human_table()?,
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::ListOutput;
    use crate::{
        cli::Format, database::Database, license::Licenses, repo_index::RepoIndex,
        versions::RudderVersion, webapp::Webapp,
    };
    use pretty_assertions::assert_eq;
    use std::{
        fs::read_to_string,
        path::{Path, PathBuf},
    };

    #[test]
    fn it_lists_plugins_as_json() {
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
        out.clone().display(Format::Json).unwrap();

        let json_ref: serde_json::Value =
            serde_json::from_str(&read_to_string("tests/cli/cli-list-out.json").unwrap()).unwrap();
        let json: serde_json::Value = serde_json::from_str(&out.json().unwrap()).unwrap();

        assert_eq!(json, json_ref);
    }
}
