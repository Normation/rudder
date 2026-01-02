// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

/// Database using SQLite
///
/// The module is responsible for maintaining the database: schema upgrade, cleanup, etc.
///
use crate::MODULE_NAME;
use crate::{output::Report, state::UpdateStatus};
use chrono::{DateTime, Duration, SecondsFormat, Utc};
use rudder_module_type::rudder_debug;
use rusqlite::{self, Connection, Row};
#[cfg(unix)]
use std::fs::Permissions;
#[cfg(unix)]
use std::os::unix::prelude::PermissionsExt;
use std::{
    fmt::{Display, Formatter},
    fs,
    path::{Path, PathBuf},
};

const DB_EXTENSION: &str = "sqlite";

pub struct PackageDatabase {
    conn: Connection,
}

/// Information about an event stored in the database
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Event {
    pub(crate) id: String,
    pub(crate) campaign_name: String,
    pub(crate) status: UpdateStatus,
    pub(crate) scheduled_datetime: DateTime<Utc>,
    pub(crate) run_datetime: Option<DateTime<Utc>>,
    pub(crate) report_datetime: Option<DateTime<Utc>>,
    pub(crate) report: Option<Report>,
}

impl Display for Event {
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        f.write_str(&format!("\nEvent ID: {}\n", self.id))?;
        f.write_str(&format!("Campaign name: {}\n", self.campaign_name))?;
        f.write_str(&format!("Status: {}\n", self.status))?;
        f.write_str(&format!(
            "Scheduled: {}\n",
            self.scheduled_datetime
                .to_rfc3339_opts(SecondsFormat::Secs, true)
        ))?;
        if let Some(d) = self.run_datetime {
            f.write_str(&format!(
                "Run: {}\n",
                d.to_rfc3339_opts(SecondsFormat::Secs, true)
            ))?;
        }
        if let Some(d) = self.report_datetime {
            f.write_str(&format!(
                "Reported: {}\n",
                d.to_rfc3339_opts(SecondsFormat::Secs, true)
            ))?;
        }
        if let Some(ref r) = self.report {
            f.write_str(&format!(
                "Report: {}\n",
                serde_json::to_string_pretty(&r).unwrap()
            ))?;
        }
        Ok(())
    }
}

impl PackageDatabase {
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
        // Run migrations that can't be expressed in the .sql file
        s.migration_add_pid()?;

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
        let schema = include_str!("packages.sql");
        self.conn.execute_batch(schema)?;
        Ok(())
    }

    fn migration_add_pid(&mut self) -> Result<(), rusqlite::Error> {
        rudder_debug!("Running pid migration");
        let r = self
            .conn
            .query_row("select pid from update_events limit 1", [], |_| Ok(()));
        match r {
            Ok(_) => (),
            Err(rusqlite::Error::QueryReturnedNoRows) => (),
            Err(_) => {
                rudder_debug!("Adding the pid column");
                self.conn
                    .execute("alter table update_events add pid integer", ())?;
            }
        }
        Ok(())
    }

    /// Purge entries, regardless of their status.
    pub fn clean(&self, retention: Duration) -> Result<(), rusqlite::Error> {
        rudder_debug!("Purging old events");
        self.conn.execute(
            "delete from update_events where datetime(run_datetime) < datetime('now', ?1)",
            (&format!("-{} minutes", retention.num_minutes()),),
        )?;
        Ok(())
    }

    /// Lock for the current process on a given campaign
    ///
    pub fn lock(&mut self, pid: u32, event_id: &str) -> Result<Option<u32>, rusqlite::Error> {
        let tx = self.conn.transaction()?;
        let r = tx.query_row(
            "select pid from update_events where event_id = ?1",
            [&event_id],
            |row| {
                let v: Option<u32> = row.get(0)?;
                Ok(v)
            },
        );
        let current_pid = match r {
            Ok(pid) => pid,
            Err(rusqlite::Error::QueryReturnedNoRows) => None,
            Err(e) => return Err(e),
        };
        match current_pid {
            None => {
                rudder_debug!("Setting lock for event {} to process {}", event_id, pid);
                tx.execute(
                    "update update_events set pid = ?1 where event_id = ?2",
                    (pid, &event_id),
                )?;
            }
            Some(p) => rudder_debug!("Lock is already set by process {}", p),
        }
        tx.commit()?;
        Ok(current_pid)
    }

    /// Unlock for the current process on a given campaign
    ///
    pub fn unlock(&mut self, event_id: &str) -> Result<(), rusqlite::Error> {
        let null: Option<u32> = None;
        rudder_debug!("Removing lock for event {}", event_id);

        self.conn
            .execute(
                "update update_events set pid = ?1 where event_id = ?2",
                (null, &event_id),
            )
            .map(|_| ())
    }

    pub fn get_status(&self, event_id: &str) -> Result<Option<UpdateStatus>, rusqlite::Error> {
        let r = self.conn.query_row(
            "select status from update_events where event_id = ?1",
            [&event_id],
            |row| {
                let v: String = row.get(0)?;
                let p: UpdateStatus = v.parse().unwrap();
                Ok(p)
            },
        );
        match r {
            Ok(p) => Ok(Some(p)),
            Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
            Err(e) => Err(e),
        }
    }

    /// Schedule an event
    ///
    pub fn schedule_event(
        &mut self,
        event_id: &str,
        campaign_name: &str,
        schedule_datetime: DateTime<Utc>,
    ) -> Result<(), rusqlite::Error> {
        self.conn.execute(
                "insert into update_events (event_id, campaign_name, status, schedule_datetime) values (?1, ?2, ?3, ?4)",
                (&event_id, &campaign_name, UpdateStatus::ScheduledUpdate.to_string(), schedule_datetime.to_rfc3339()),
            ).map(|_| ())
    }

    /// Start an event
    ///
    /// The update also acts as the locking mechanism
    ///
    /// We take the actual start time, which is >= scheduled.
    pub fn start_event(
        &mut self,
        event_id: &str,
        start_datetime: DateTime<Utc>,
    ) -> Result<(), rusqlite::Error> {
        self.conn
            .execute(
                "update update_events set status = ?1, run_datetime = ?2 where event_id = ?3",
                (
                    UpdateStatus::RunningUpdate.to_string(),
                    start_datetime.to_rfc3339(),
                    &event_id,
                ),
            )
            .map(|_| ())
    }

    /// Schedule post-event action. Can happen after a reboot in a separate run.
    ///
    pub fn schedule_post_event(
        &mut self,
        event_id: &str,
        report: &Report,
    ) -> Result<(), rusqlite::Error> {
        self.conn
            .execute(
                "update update_events set status = ?1, report = ?2 where event_id = ?3",
                (
                    UpdateStatus::PendingPostActions.to_string(),
                    serde_json::to_string(report).unwrap(),
                    &event_id,
                ),
            )
            .map(|_| ())
    }

    /// Start post-event action. Can happen after a reboot in a separate run.
    ///
    /// The update also acts as the locking mechanism
    pub fn post_event(&mut self, event_id: &str) -> Result<(), rusqlite::Error> {
        self.conn
            .execute(
                "update update_events set status = ?1 where event_id = ?2",
                (UpdateStatus::RunningPostActions.to_string(), &event_id),
            )
            .map(|_| ())
    }

    /// Assumes the report exists, fails otherwise
    pub fn get_report(&self, event_id: &str) -> Result<Report, rusqlite::Error> {
        self.conn.query_row(
            "select report from update_events where event_id = ?1",
            [&event_id],
            |row| {
                let v: String = row.get(0)?;
                let p: Report = serde_json::from_str(&v).unwrap();
                Ok(p)
            },
        )
    }

    /// Mark the event as completed
    pub fn completed(
        &self,
        event_id: &str,
        report_datetime: DateTime<Utc>,
        report: &Report,
    ) -> Result<(), rusqlite::Error> {
        self.conn.execute(
            "update update_events set status = ?1, report_datetime = ?2, report = ?3 where event_id = ?4",
            (
                UpdateStatus::Completed.to_string(),
                report_datetime.to_rfc3339(),
                serde_json::to_string(report).unwrap(),
                &event_id,
            ),
        )?;
        Ok(())
    }

    /// Get data from all a specific event
    pub fn event(&self, id: String) -> Result<Event, rusqlite::Error> {
        let mut stmt = self.conn.prepare("select event_id,campaign_name,status,schedule_datetime,run_datetime,report_datetime,report from update_events where event_id like ?1 || '%'")?;
        stmt.query_row([id], Self::extract_event)
    }

    /// Get data from all stored events
    pub fn events(&self) -> Result<Vec<Event>, rusqlite::Error> {
        let mut stmt = self.conn.prepare("select event_id,campaign_name,status,schedule_datetime,run_datetime,report_datetime,report from update_events order by schedule_datetime asc")?;
        let event_map = stmt.query_map([], Self::extract_event)?;
        let events: Result<Vec<Event>, rusqlite::Error> = event_map.collect();
        events
    }

    fn extract_event(row: &Row) -> Result<Event, rusqlite::Error> {
        Ok(Event {
            id: row.get(0)?,
            campaign_name: row.get(1)?,
            status: {
                let v: String = row.get(2)?;
                v.parse().unwrap()
            },
            scheduled_datetime: {
                let v: String = row.get(3)?;
                DateTime::from(DateTime::parse_from_rfc3339(&v).unwrap())
            },
            run_datetime: {
                let v: Option<String> = row.get(4)?;
                v.map(|s| DateTime::from(DateTime::parse_from_rfc3339(&s).unwrap()))
            },
            report_datetime: {
                let v: Option<String> = row.get(5)?;
                v.map(|s| DateTime::from(DateTime::parse_from_rfc3339(&s).unwrap()))
            },
            report: {
                let v: Option<String> = row.get(6)?;
                v.map(|s| serde_json::from_str(&s).unwrap())
            },
        })
    }
}

#[cfg(test)]
mod tests {
    use super::{Event, PackageDatabase};
    use crate::{output::Report, state::UpdateStatus};
    use chrono::{Duration, Utc};
    use pretty_assertions::assert_eq;
    use rusqlite::Connection;
    use std::ops::Add;
    #[cfg(unix)]
    use std::os::unix::prelude::PermissionsExt;

    #[test]
    fn new_creates_new_database() {
        let t = tempfile::tempdir().unwrap();
        PackageDatabase::new(Some(t.path())).unwrap();
        let db_name = PackageDatabase::db_name();
        let p = t.path().join(db_name);
        assert!(p.exists());
        #[cfg(unix)]
        assert_eq!(p.metadata().unwrap().permissions().mode(), 0o100600);
    }

    #[test]
    fn migration_add_pid_column() {
        let conn = Connection::open_in_memory().unwrap();
        conn.execute("create table update_events (id integer primary key)", ())
            .unwrap();

        let mut db = PackageDatabase::open_existing(conn);
        db.migration_add_pid().unwrap();
        // can run twice
        db.migration_add_pid().unwrap();

        let conn = db.into_connection();
        let r = conn.execute("select pid from update_events", ());
        assert!(r.is_ok());
    }

    #[test]
    fn clean_removes_expired_entries() {
        let db = PackageDatabase::new(None).unwrap();
        let retention = Duration::minutes(30);
        // Ensure that it works even when the database is empty
        db.clean(retention).unwrap();
    }

    #[test]
    fn locks_locks() {
        let mut db = PackageDatabase::new(None).unwrap();
        let event_id = "TEST";
        let campaign_id = "CAMPAIGN";
        let now = Utc::now();

        db.schedule_event(event_id, campaign_id, now).unwrap();
        assert_eq!(db.lock(0, event_id).unwrap(), None);
        assert_eq!(db.lock(0, event_id).unwrap(), Some(0));
    }

    #[test]
    fn unlock_unlocks() {
        let mut db = PackageDatabase::new(None).unwrap();
        let event_id = "TEST";
        let campaign_id = "CAMPAIGN";
        let now = Utc::now();

        db.schedule_event(event_id, campaign_id, now).unwrap();
        assert_eq!(db.lock(0, event_id).unwrap(), None);
        db.unlock(event_id).unwrap();
        assert_eq!(db.lock(0, event_id).unwrap(), None);
    }

    #[test]
    fn it_gets_status_regardless_of_event_id_case() {
        let mut db = PackageDatabase::new(None).unwrap();
        let event_id = "TEST";
        let campaign_id = "CAMPAIGN";
        let now = Utc::now();
        assert_eq!(db.get_status(event_id).unwrap(), None);
        db.schedule_event(event_id, campaign_id, now).unwrap();
        assert_eq!(
            db.get_status(event_id).unwrap().unwrap(),
            UpdateStatus::ScheduledUpdate
        );
        assert_eq!(
            db.get_status(&event_id.to_lowercase()).unwrap().unwrap(),
            UpdateStatus::ScheduledUpdate
        );
    }

    #[test]
    fn start_event_inserts_and_sets_running_update() {
        let mut db = PackageDatabase::new(None).unwrap();
        let event_id = "TEST";
        let campaign_id = "CAMPAIGN";
        let now = Utc::now();
        db.schedule_event(event_id, campaign_id, now).unwrap();
        db.start_event(event_id, now).unwrap();
        assert_eq!(
            db.get_status(event_id).unwrap().unwrap(),
            UpdateStatus::RunningUpdate
        )
    }

    #[test]
    fn store_and_get_report_works() {
        let mut db = PackageDatabase::new(None).unwrap();
        let report = Report::new();
        let event_id = "TEST";
        let campaign_name = "CAMPAIGN";
        let schedule = Utc::now();
        let start = schedule.add(Duration::minutes(2));
        db.schedule_event(event_id, campaign_name, schedule)
            .unwrap();
        db.start_event(event_id, start).unwrap();
        db.schedule_post_event(event_id, &report).unwrap();

        // Now get the data back and see if it matches
        let got_report = db.get_report(event_id).unwrap();
        assert_eq!(report, got_report);

        let events = db.events().unwrap();
        let event = Event {
            id: event_id.to_string(),
            campaign_name: campaign_name.to_string(),
            status: UpdateStatus::PendingPostActions,
            scheduled_datetime: schedule,
            run_datetime: Some(start),
            report_datetime: None,
            report: Some(report.clone()),
        };
        assert_eq!(events, vec![event]);

        let event = db.event("TES".to_string()).unwrap();
        let ref_event = Event {
            id: event_id.to_string(),
            campaign_name: campaign_name.to_string(),
            status: UpdateStatus::PendingPostActions,
            scheduled_datetime: schedule,
            run_datetime: Some(start),
            report_datetime: None,
            report: Some(report),
        };
        assert_eq!(event, ref_event);
    }
}
