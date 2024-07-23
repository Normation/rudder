// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

use std::{
    fmt,
    path::Path,
    time::{Instant, SystemTime},
};

use chrono::Duration;
use rudder_module_type::rudder_debug;
use rusqlite::{self, params, Connection};

/// Database using SQLite
///
/// The module is responsible for maintaining the database: schema upgrade, cleanup, etc.
///
use crate::SystemUpdate;
use crate::{campaign::UpdateStatus, output::Report, package_manager::PackageList};

/// Reuse the path used by CFEngine for storing agent state
///
const DB_NAME: &str = "system-updates.sqlite";

pub struct PackageDatabase {
    conn: Connection,
}

impl PackageDatabase {
    /// Open the database, creating it if necessary.
    /// When no path is provided, the database is created in memory.
    pub fn new(path: Option<&Path>) -> Result<Self, rusqlite::Error> {
        let conn = if let Some(p) = path {
            let full_path = p.join(DB_NAME);
            rudder_debug!(
                "Opening database {} (sqlite {})",
                full_path.display(),
                rusqlite::version()
            );
            Connection::open(full_path)
        } else {
            rudder_debug!(
                "Opening in-memory database (sqlite {})",
                rusqlite::version()
            );
            Connection::open_in_memory()
        }?;
        let schema = include_str!("packages.sql");
        conn.execute(schema, ())?;
        Ok(Self { conn })
    }

    /// Purge entries
    pub fn clean(&self, retention: Duration) -> Result<(), rusqlite::Error> {
        rudder_debug!("Purging old events");
        self.conn.execute(
            "DELETE FROM update_events WHERE date(run_datetime) < date('now', ?1)",
            (&format!("-{} minutes", retention.num_minutes()),),
        )?;
        Ok(())
    }

    /// Start an event
    ///
    /// The insertion also acts as the locking mechanism
    pub fn start_event(
        &mut self,
        event_id: &str,
        campaign_name: &str,
    ) -> Result<bool, rusqlite::Error> {
        let tx = self.conn.transaction()?;

        let r = tx.query_row(
            "SELECT id FROM update_events WHERE event_id = ?1",
            [&event_id],
            |row| Ok(()),
        );
        let already_there = match r {
            Ok(_) => true,
            Err(rusqlite::Error::QueryReturnedNoRows) => false,
            Err(e) => return Err(e),
        };
        if !already_there {
            tx.execute(
                "INSERT INTO update_events (event_id, campaign_name, status, run_datetime) VALUES (?1, ?2, ?3, datetime('now'))",
                (&event_id, &campaign_name, UpdateStatus::Running.to_string()),
            )?;
        }

        tx.commit()?;
        Ok(already_there)
    }

    pub fn status(&mut self, event_id: &str) -> Result<UpdateStatus, rusqlite::Error> {
        let r = self.conn.query_row(
            "SELECT status FROM update_events WHERE event_id = ?1",
            [&event_id],
            |row| {
                let s: String = row.get_unwrap(0);
                Ok(s.parse().unwrap())
            },
        )?;
        Ok(r)
    }

    pub fn store_report(&self, event_id: &str, report: &Report) -> Result<(), rusqlite::Error> {
        self.conn.execute(
            "UPDATE update_events SET report = ?1 where event_id = ?2",
            (serde_json::to_string(report).unwrap(), &event_id),
        )?;
        Ok(())
    }

    pub fn get_report(&self, event_id: &str) -> Result<Report, rusqlite::Error> {
        self.conn.query_row(
            "SELECT report FROM update_events WHERE event_id = ?1",
            [&event_id],
            |row| {
                let v: String = row.get_unwrap(0);
                let p: Report = serde_json::from_str(&v).unwrap();
                Ok(p)
            },
        )
    }
}

#[cfg(test)]
mod tests {
    use std::{collections::HashMap, path::Path};

    use chrono::Duration;

    use super::{PackageDatabase, DB_NAME};
    use crate::{
        campaign::UpdateStatus,
        output::Report,
        package_manager::{PackageId, PackageList, PackageManager},
    };

    #[test]
    fn new_creates_new_database() {
        let t = tempfile::tempdir().unwrap();
        let db = PackageDatabase::new(Some(t.path())).unwrap();
        assert!(t.path().join(DB_NAME).exists());
    }

    #[test]
    fn clean_removes_expired_entries() {
        let db = PackageDatabase::new(None).unwrap();
        let retention = Duration::minutes(30);
        // Ensure that it works even when the database is empty
        db.clean(retention).unwrap();
    }

    #[test]
    fn start_event_inserts_and_returns_false_for_new_events() {
        let mut db = PackageDatabase::new(None).unwrap();
        let event_id = "TEST";
        let campaign_id = "CAMPAIGN";
        // If the event was not present before, this should be false
        assert_eq!(false, db.start_event(event_id, campaign_id).unwrap());
        assert_eq!(true, db.start_event(event_id, campaign_id).unwrap());
    }

    #[test]
    fn store_and_get_report_works() {
        let mut db = PackageDatabase::new(None).unwrap();
        let report = Report::new();
        let event_id = "TEST";
        let campaign_id = "CAMPAIGN";
        db.start_event(event_id, campaign_id).unwrap();
        db.store_report(event_id, &report).unwrap();

        // Now get the data back and see if it matches
        let got_report = db.get_report(event_id).unwrap();
        assert_eq!(report, got_report);
    }
}
