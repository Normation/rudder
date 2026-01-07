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
    pub fn insert_event(
        &mut self,
        event_id: &str,
        event_type: &str,
        event_name: &str,
        state: EventState,
        schedule_datetime: DateTime<Utc>,
    ) -> Result<(), rusqlite::Error> {
        self.conn.execute(
                "insert into schedule_events (event_id, event_type, event_name, state, datetime) values (?1, ?2, ?3, ?4, ?5)",
                (event_id, event_type, event_name, state.to_string(), schedule_datetime.to_rfc3339()),
            ).map(|_| ())
    }

    /// Start an event
    ///
    /// The update also acts as the locking mechanism
    ///
    /// We take the actual start time, which is >= scheduled.
    pub fn finish_event(
        &mut self,
        event_id: &str,
        datetime: DateTime<Utc>,
    ) -> Result<(), rusqlite::Error> {
        self.conn
            .execute(
                "update schedule_events set status = ?1, datetime = ?2 where event_id = ?3",
                (
                    EventState::Done.to_string(),
                    datetime.to_rfc3339(),
                    &event_id,
                ),
            )
            .map(|_| ())
    }
}

#[cfg(test)]
mod tests {
    use super::{Event, EventDatabase, EventState};
    use chrono::{Duration, Utc};
    use pretty_assertions::assert_eq;
    use rusqlite::Connection;
    use std::{ops::Add, os::unix::prelude::PermissionsExt};

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
        let event_id = "TEST";
        let event_type = "TYPE";
        let event_name = "NAME";
        let now = Utc::now();
        assert_eq!(db.get_state(event_id).unwrap(), None);
        db.insert_event(event_id, event_type, event_name, EventState::Scheduled, now)
            .unwrap();
        assert_eq!(
            db.get_state(event_id).unwrap().unwrap(),
            EventState::Scheduled
        );
        assert_eq!(
            db.get_state(&event_id.to_lowercase()).unwrap().unwrap(),
            EventState::Scheduled
        );
    }
}
