// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

use anyhow::Result;

use crate::{cli::Format, database::Database, repo_index::RepoIndex, webapp::Webapp};

/// Content we want to display about each plugin
pub struct ListEntry<'a> {
    name: &'a str,
    version: &'a str,
    available: Option<&'a str>,
    enabled: bool,
}

pub fn list(
    all: bool,
    enabled: bool,
    format: Format,
    db: &Database,
    index: &RepoIndex,
    webapp: &Webapp,
) -> Result<()> {

    dbg!(index);

    for p in index.inner().iter() {
        dbg!(&p.metadata.name);
        dbg!(&p.metadata.version.to_string());
    }
    

    
    //dbg!(db);
    let enabled = webapp.jars()?;
    //dbg!(enabled);


    
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
