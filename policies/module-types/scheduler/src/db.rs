// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023-2026 Normation SAS

/// Database using SQLite
///
/// The module is responsible for maintaining the database: schema upgrade, cleanup, etc.
///
use crate::MODULE_NAME;
use crate::event::Event;
use chrono::{DateTime, Duration, Utc};
use rudder_module_type::rudder_debug;
use rusqlite::{self, Connection};
use std::str::FromStr;
use std::{
    fmt::{Display, Formatter},
    fs,
    fs::Permissions,
    os::unix::prelude::PermissionsExt,
    path::{Path, PathBuf},
};

const DB_EXTENSION: &str = "sqlite";

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum EventState {
    Scheduled,
    Done,
    Canceled,
}

impl FromStr for EventState {
    type Err = std::io::Error;

    fn from_str(s: &str) -> anyhow::Result<Self, Self::Err> {
        match s {
            "scheduled" => Ok(Self::Scheduled),
            "done" => Ok(Self::Done),
            "canceled" => Ok(Self::Canceled),
            _ => Err(std::io::Error::new(
                std::io::ErrorKind::InvalidInput,
                "Invalid input",
            )),
        }
    }
}

impl Display for EventState {
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        let s = match self {
            EventState::Scheduled => "scheduled",
            EventState::Done => "done",
            EventState::Canceled => "canceled",
        };
        write!(f, "{}", s)
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

    #[cfg(test)]
    fn open_existing(conn: Connection) -> Self {
        Self { conn }
    }

    #[cfg(test)]
    fn into_connection(self) -> Connection {
        self.conn
    }

    fn init_schema(&mut self) -> Result<(), rusqlite::Error> {
        let schema = include_str!("event.sql");
        self.conn.execute_batch(schema)?;
        Ok(())
    }

    /// Purge entries, regardless of their status.
    pub fn clean(&self, retention: Duration) -> Result<(), rusqlite::Error> {
        rudder_debug!("Purging old events");
        self.conn.execute(
            // FIXME: which date to use?
            "delete from schedule_events where datetime(created) < datetime('now', ?1)",
            (&format!("-{} minutes", retention.num_minutes()),),
        )?;
        Ok(())
    }

    pub fn get_state(&self, event_id: &str) -> Result<Option<EventState>, rusqlite::Error> {
        let r = self.conn.query_row(
            "select state from schedule_events where event_id = ?1",
            [&event_id],
            |row| {
                let v: String = row.get(0)?;
                let p: EventState = v.parse().unwrap();
                Ok(p)
            },
        );
        match r {
            Ok(p) => Ok(Some(p)),
            Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
            Err(e) => Err(e),
        }
    }

    /// Insert an event, either scheduled or immediate
    ///
    pub fn insert_event(&mut self, e: &Event, state: EventState) -> Result<(), rusqlite::Error> {
        self.conn.execute(
                "insert into schedule_events (event_id, schedule_id, event_type, event_name, state, datetime) values (?1, ?2, ?3, ?4, ?5, ?6)",
                (&e.id, &e.schedule_id, &e.e_type, &e.name, state.to_string(), e.datetime.map(|d|d.to_rfc3339())),
            ).map(|_| ())
    }

    pub fn finish_event(
        &mut self,
        event_id: &str,
        datetime: DateTime<Utc>,
    ) -> Result<(), rusqlite::Error> {
        self.conn
            .execute(
                "update schedule_events set state = ?1, datetime = ?2 where event_id = ?3",
                (
                    EventState::Done.to_string(),
                    datetime.to_rfc3339(),
                    &event_id,
                ),
            )
            .map(|_| ())
    }

    pub fn cancel_other_events(&mut self, event_ids: &[&str]) -> Result<(), rusqlite::Error> {
        let ids = event_ids.join(",");
        let query = format!(
            "update schedule_events set state = ?1 where event_id not in ({})",
            ids
        );
        // FIXME ??: SQL injection vulnerability
        self.conn
            .execute(&query, (EventState::Canceled.to_string(),))
            .map(|_| ())
    }
}

#[cfg(test)]
mod tests {
    use super::{Event, EventDatabase, EventState};
    use crate::event::EventSchedule;
    use chrono::{Duration, Utc};
    use pretty_assertions::assert_eq;
    
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
    fn it_gets_status_regardless_of_event_id_case() {
        let mut db = EventDatabase::new(None).unwrap();
        let id = "ID";
        let e = test_event(id);
        assert_eq!(db.get_state(id).unwrap(), None);
        db.insert_event(&e, EventState::Scheduled).unwrap();
        assert_eq!(db.get_state(id).unwrap().unwrap(), EventState::Scheduled);
        assert_eq!(
            db.get_state(&id.to_lowercase()).unwrap().unwrap(),
            EventState::Scheduled
        );
    }

    #[test]
    fn it_updates_events() {
        let mut db = EventDatabase::new(None).unwrap();
        let id = "ID";
        let e = test_event(id);

        assert_eq!(db.get_state(id).unwrap(), None);
        db.insert_event(&e, EventState::Scheduled).unwrap();
        assert_eq!(db.get_state(id).unwrap().unwrap(), EventState::Scheduled);
        db.finish_event(id, Utc::now().add(Duration::minutes(5)))
            .unwrap();
        assert_eq!(db.get_state(id).unwrap().unwrap(), EventState::Done);
    }
}
