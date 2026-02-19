// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023-2026 Normation SAS

/// Database using SQLite
///
/// The module is responsible for maintaining the database: schema upgrade, cleanup, etc.
///
use crate::MODULE_NAME;
use crate::event::Event;
use chrono::{DateTime, Duration, SecondsFormat, Utc};
use rudder_module_type::rudder_debug;
use rusqlite::{self, Connection, Row};
use std::fmt::{Display, Formatter};
use std::{
    fs,
    path::{Path, PathBuf},
};
#[cfg(unix)]
use std::{fs::Permissions, os::unix::prelude::PermissionsExt};

const DB_EXTENSION: &str = "sqlite";

/// An event stored in the database
pub struct DbEvent {
    pub id: String,
    pub schedule_id: String,
    pub name: String,
    pub schedule_type: String,
    pub schedule: String,
    pub created: DateTime<Utc>,
    pub not_before: Option<DateTime<Utc>>,
    pub not_after: Option<DateTime<Utc>>,
    pub run_time: Option<DateTime<Utc>>,
}

impl Display for DbEvent {
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        f.write_str(&format!("Event ID: {}\n", self.id))?;
        f.write_str(&format!("Schedule ID: {}\n", self.schedule_id))?;
        f.write_str(&format!("Type: {}\n", self.schedule_type))?;
        f.write_str(&format!("Name: {}\n", self.name))?;
        f.write_str(&format!("Schedule: {}\n", self.schedule))?;
        if let Some(d) = self.run_time {
            f.write_str(&format!(
                "Triggered: {}\n",
                d.to_rfc3339_opts(SecondsFormat::Secs, true)
            ))?;
        } else {
            f.write_str("Triggered: not yet\n")?;
        }
        if let Some(d) = self.not_before {
            f.write_str(&format!(
                "Not before: {}\n",
                d.to_rfc3339_opts(SecondsFormat::Secs, true)
            ))?;
        }
        if let Some(d) = self.not_after {
            f.write_str(&format!(
                "Not after: {}\n",
                d.to_rfc3339_opts(SecondsFormat::Secs, true)
            ))?;
        }
        f.write_str(&format!(
            "Inserted: {}\n",
            self.created.to_rfc3339_opts(SecondsFormat::Secs, true)
        ))?;

        Ok(())
    }
}

pub struct EventDatabase {
    conn: Connection,
}

impl EventDatabase {
    fn db_name() -> PathBuf {
        Path::new(MODULE_NAME).with_extension(DB_EXTENSION)
    }

    /// Open the database, creating it if necessary.
    /// When no path is provided, the database is created in memory.
    pub fn new(path: Option<&Path>) -> anyhow::Result<Self> {
        let conn = if let Some(p) = path {
            fs::create_dir_all(p)?;
            let full_path = p.join(Self::db_name());
            rudder_debug!(
                "Opening database {} (sqlite {})",
                full_path.display(),
                rusqlite::version()
            );
            let conn = Connection::open(full_path.as_path());
            // Set lowest permissions
            #[cfg(unix)]
            fs::set_permissions(full_path.as_path(), Permissions::from_mode(0o600))?;
            conn
        } else {
            rudder_debug!(
                "Opening in-memory database (sqlite {})",
                rusqlite::version()
            );
            Connection::open_in_memory()
        }?;
        let mut s = Self { conn };

        // Initialize schema
        s.init_schema()?;

        Ok(s)
    }

    fn init_schema(&mut self) -> Result<(), rusqlite::Error> {
        let schema = include_str!("event.sql");
        self.conn.execute_batch(schema)?;
        Ok(())
    }

    /// Purge entries, regardless of their status, based on their inserted datetime.
    pub fn clean(&self, retention: Duration) -> Result<(), rusqlite::Error> {
        rudder_debug!("Purging old events");
        self.conn.execute(
            "delete from schedule_events where datetime(created) < datetime('now', ?1)",
            (&format!("-{} minutes", retention.num_minutes()),),
        )?;
        Ok(())
    }

    pub fn get_run_time(&self, event_id: &str) -> Result<Option<DateTime<Utc>>, rusqlite::Error> {
        let r = self.conn.query_row(
            "select run_time from schedule_events where event_id = ?1",
            [&event_id],
            |row| {
                let v: Option<String> = row.get(0)?;
                Ok(v.map(|d| d.parse::<DateTime<Utc>>().unwrap()))
            },
        );
        match r {
            Ok(p) => Ok(p),
            Err(e) => Err(e),
        }
    }

    pub fn insert_if_new(&mut self, e: &Event, now: DateTime<Utc>) -> Result<(), rusqlite::Error> {
        let tx = self.conn.transaction()?;
        let r = tx.query_row(
            "select event_id from schedule_events where event_id = ?1",
            [&e.id],
            |_| Ok(()),
        );
        match r {
            Ok(_) => (),
            Err(rusqlite::Error::QueryReturnedNoRows) => tx.execute(
                "insert into schedule_events (event_id, schedule_id, schedule_type, schedule, event_name, created) values (?1, ?2, ?3, ?4, ?5, ?6)",
                (&e.id, &e.schedule_id, &e.schedule_type, &e.schedule.to_string(), &e.name, now.to_rfc3339()),
            ).map(|_| ())?,
            Err(e) => {
                return Err(e);
            }
        }
        tx.commit()?;
        Ok(())
    }

    pub fn run_event(
        &mut self,
        event_id: &str,
        run_time: DateTime<Utc>,
    ) -> Result<(), rusqlite::Error> {
        self.conn
            .execute(
                "update schedule_events set run_time = ?1 where event_id = ?2",
                (run_time.to_rfc3339(), &event_id),
            )
            .map(|_| ())
    }

    pub fn events(&self) -> Result<Vec<DbEvent>, rusqlite::Error> {
        let mut stmt = self
            .conn
            .prepare("select event_id,schedule_id,schedule_type,schedule,event_name,created,run_time,not_before,not_after from schedule_events order by created asc")?;
        let event_map = stmt.query_map([], Self::extract_event)?;
        let events: Result<Vec<DbEvent>, rusqlite::Error> = event_map.collect();
        events
    }

    fn extract_event(row: &Row) -> Result<DbEvent, rusqlite::Error> {
        Ok(DbEvent {
            id: row.get(0)?,
            schedule_id: row.get(1)?,
            schedule_type: row.get(2)?,
            schedule: row.get(3)?,
            name: row.get(4)?,
            created: {
                let v: String = row.get(5)?;
                DateTime::from(DateTime::parse_from_rfc3339(&v).unwrap())
            },
            run_time: {
                let v: Option<String> = row.get(6)?;
                v.map(|d| DateTime::from(DateTime::parse_from_rfc3339(&d).unwrap()))
            },
            not_before: {
                let v: Option<String> = row.get(7)?;
                v.map(|d| DateTime::from(DateTime::parse_from_rfc3339(&d).unwrap()))
            },
            not_after: {
                let v: Option<String> = row.get(8)?;
                v.map(|d| DateTime::from(DateTime::parse_from_rfc3339(&d).unwrap()))
            },
        })
    }
}

#[cfg(test)]
mod tests {
    use super::{Event, EventDatabase};
    use crate::event::EventSchedule;
    use chrono::{Duration, Utc};
    use pretty_assertions::assert_eq;

    #[cfg(unix)]
    use std::{ops::Add, os::unix::prelude::PermissionsExt};

    fn test_event(id: &str) -> Event {
        Event::new(
            id.to_string(),
            "SCHEDULE_ID".to_string(),
            "NAME".to_string(),
            "TYPE".to_string(),
            EventSchedule::Once,
            None,
            None,
        )
    }

    #[test]
    fn new_creates_new_database() {
        let t = tempfile::tempdir().unwrap();
        EventDatabase::new(Some(t.path())).unwrap();
        let db_name = EventDatabase::db_name();
        let p = t.path().join(db_name);
        assert!(p.exists());
        #[cfg(unix)]
        assert_eq!(p.metadata().unwrap().permissions().mode(), 0o100600);
    }

    #[test]
    fn clean_removes_expired_entries() {
        let db = EventDatabase::new(None).unwrap();
        let retention = Duration::minutes(30);
        // Ensure that it works even when the database is empty
        db.clean(retention).unwrap();
    }

    #[test]
    fn it_gets_datetime_regardless_of_event_id_case() {
        let mut db = EventDatabase::new(None).unwrap();
        let id = "ID";
        let e = test_event(id);
        assert!(db.get_run_time(id).is_err());
        db.insert_if_new(&e, Utc::now()).unwrap();
        assert_eq!(db.get_run_time(id).unwrap(), None);
        assert_eq!(db.get_run_time(&id.to_lowercase()).unwrap(), None);
    }

    #[test]
    fn it_updates_events() {
        let mut db = EventDatabase::new(None).unwrap();
        let id = "ID";
        let e = test_event(id);

        assert!(db.get_run_time(id).is_err());
        db.insert_if_new(&e, Utc::now()).unwrap();
        assert_eq!(db.get_run_time(id).unwrap(), None);
        let run_time = Utc::now().add(Duration::minutes(5));
        db.run_event(id, run_time).unwrap();
        assert_eq!(db.get_run_time(id).unwrap(), Some(run_time));
    }
}
