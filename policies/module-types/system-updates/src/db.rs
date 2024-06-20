// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

// On veut passer les infos d'expiration de cache
// On veut passer le dossier de stockage de state
// Le module est responsable du schéma
// Il faut une table de clé-valeur en plus

use crate::SystemUpdate;
use anyhow::Result;
use rusqlite::{self, params, Connection};
use std::fmt;
use std::path::Path;
use std::time::{Instant, SystemTime};

const DB_DIR: &str = "/var/rudder/cfengine-community/state/";
const DB_NAME: &str = "system-updates.db";

#[derive(Debug)]
struct PackageQuery {
    name: String,
    // None means any
    version: Option<String>,
    // None means any
    architecture: Option<String>,
}

#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub enum CacheType {
    Installed,
    AvailableUpdates,
}

impl fmt::Display for CacheType {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "{}",
            match self {
                CacheType::Installed => "installed",
                CacheType::AvailableUpdates => "updates",
            }
        )
    }
}

struct PackageCache {
    db: Connection,
    cache_type: CacheType,
}

impl PackageCache {
    /// Open the database, creating it if necessary, but do not populate it.
    /// When no path is provided, the database is created in memory.
    pub fn new(cache_type: CacheType, path: Option<&Path>) -> Result<Self> {
        let conn = if let Some(p) = path {
            Connection::open(p)
        } else {
            Connection::open_in_memory()
        }?;
        let schema = include_str!("packages.sql");
        conn.execute(schema, ())?;
        Ok(Self {
            cache_type,
            db: conn,
        })
    }

    pub fn refresh(packages: &[SystemUpdate]) -> Result<()> {
        Ok(())
    }

    pub fn contains(package: &PackageQuery) -> Result<bool> {
        Ok(false)
    }

    /// When the cache was refreshed
    pub fn date() -> Result<SystemTime> {
        Ok(SystemTime::now())
    }

    pub fn clear() -> Result<()> {
        Ok(())
    }
}

/*
fn main() -> Result<()> {
    conn.execute(
        "INSERT INTO person (name, data) VALUES (?1, ?2)",
        (&me.name, &me.data),
    )?;

    let mut stmt = conn.prepare("SELECT id, name, data FROM person")?;
    let person_iter = stmt.query_map([], |row| {
        Ok(Person {
            id: row.get(0)?,
            name: row.get(1)?,
            data: row.get(2)?,
        })
    })?;

    for person in person_iter {
        println!("Found person {:?}", person.unwrap());
    }
    Ok(())
}
*/

#[cfg(test)]
mod tests {
    use super::{CacheType, PackageCache};

    #[test]
    fn it_provisions_db() {
        let db = PackageCache::new(CacheType::Installed, None).unwrap();
    }
}
