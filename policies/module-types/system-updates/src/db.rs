// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

use chrono::{DateTime, Duration, SecondsFormat, Utc};
use rudder_module_type::rudder_debug;
use rusqlite::{self, Connection, Row};
use std::{
    fmt::{Display, Formatter},
    fs,
    path::{Path, PathBuf},
};

/// Database using SQLite
///
/// The module is responsible for maintaining the database: schema upgrade, cleanup, etc.
///
use crate::MODULE_NAME;
use crate::{output::Report, state::UpdateStatus};

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
            f.write_str(&format!("Report: {}\n", serde_json::to_string(&r).unwrap()))?;
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
        let db_name = Self::db_name();
        let conn = if let Some(p) = path {
            fs::create_dir_all(p)?;
            let full_path = p.join(db_name);
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
        // Migrations are included in the file
        let schema = include_str!("packages.sql");
        conn.execute(schema, ())?;
        Ok(Self { conn })
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

    /// Schedule an event
    ///
    /// The insertion also acts as the locking mechanism
    pub fn schedule_event(
        &mut self,
        event_id: &str,
        campaign_name: &str,
        schedule_datetime: DateTime<Utc>,
    ) -> Result<bool, rusqlite::Error> {
        let tx = self.conn.transaction()?;

        let r = tx.query_row(
            "select id from update_events where event_id = ?1",
            [&event_id],
            |_| Ok(()),
        );
        let already_scheduled = match r {
            Ok(_) => true,
            Err(rusqlite::Error::QueryReturnedNoRows) => false,
            Err(e) => return Err(e),
        };
        if !already_scheduled {
            tx.execute(
                "insert into update_events (event_id, campaign_name, status, schedule_datetime) values (?1, ?2, ?3, ?4)",
                (&event_id, &campaign_name, UpdateStatus::Scheduled.to_string(), schedule_datetime.to_rfc3339()),
            )?;
        }

        tx.commit()?;
        Ok(already_scheduled)
    }

    /// Start an event
    ///
    /// The update also acts as the locking mechanism
    pub fn start_event(
        &mut self,
        event_id: &str,
        start_datetime: DateTime<Utc>,
    ) -> Result<bool, rusqlite::Error> {
        let tx = self.conn.transaction()?;

        let r = tx.query_row(
            "select id from update_events where event_id = ?1 and status = ?2",
            (&event_id, UpdateStatus::Scheduled.to_string()),
            |_| Ok(()),
        );
        let pending_update = match r {
            Ok(_) => true,
            Err(rusqlite::Error::QueryReturnedNoRows) => false,
            Err(e) => return Err(e),
        };
        if pending_update {
            tx.execute(
                "update update_events set status = ?1, run_datetime = ?2 where event_id = ?3",
                (
                    UpdateStatus::Running.to_string(),
                    start_datetime.to_rfc3339(),
                    &event_id,
                ),
            )?;
        }

        tx.commit()?;
        Ok(pending_update)
    }

    /// Start post-event action. Can happen after a reboot in a separate run.
    ///
    /// The update also acts as the locking mechanism
    pub fn post_event(&mut self, event_id: &str) -> Result<bool, rusqlite::Error> {
        let tx = self.conn.transaction()?;

        let r = tx.query_row(
            "select event_id from update_events where event_id = ?1 and status = ?2",
            (&event_id, UpdateStatus::Running.to_string()),
            |_| Ok(()),
        );
        let pending_post_actions = match r {
            Ok(_) => true,
            Err(rusqlite::Error::QueryReturnedNoRows) => false,
            Err(e) => return Err(e),
        };
        if pending_post_actions {
            tx.execute(
                "update update_events set status = ?1 where event_id = ?2",
                (UpdateStatus::PendingPostActions.to_string(), &event_id),
            )?;
        }

        tx.commit()?;
        Ok(pending_post_actions)
    }

    pub fn store_report(&self, event_id: &str, report: &Report) -> Result<(), rusqlite::Error> {
        self.conn.execute(
            "update update_events set report = ?1 where event_id = ?2",
            (serde_json::to_string(report).unwrap(), &event_id),
        )?;
        Ok(())
    }

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
    pub fn sent(
        &self,
        event_id: &str,
        report_datetime: DateTime<Utc>,
    ) -> Result<(), rusqlite::Error> {
        self.conn.execute(
            "update update_events set status = ?1, report_datetime = ?2 where event_id = ?3",
            (
                UpdateStatus::Completed.to_string(),
                report_datetime.to_rfc3339(),
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
    use chrono::{Duration, Utc};
    use std::ops::Add;

    use super::{Event, PackageDatabase};
    use crate::{output::Report, state::UpdateStatus};

    #[test]
    fn new_creates_new_database() {
        let t = tempfile::tempdir().unwrap();
        PackageDatabase::new(Some(t.path())).unwrap();
        let db_name = PackageDatabase::db_name();
        assert!(t.path().join(db_name).exists());
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
        let now = Utc::now();
        // If the event was not present before, this should be false.
        assert!(!db.schedule_event(event_id, campaign_id, now).unwrap());
        assert!(db.schedule_event(event_id, campaign_id, now).unwrap());
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
        db.store_report(event_id, &report).unwrap();

        // Now get the data back and see if it matches
        let got_report = db.get_report(event_id).unwrap();
        assert_eq!(report, got_report);

        let events = db.events().unwrap();
        let event = Event {
            id: event_id.to_string(),
            campaign_name: campaign_name.to_string(),
            status: UpdateStatus::Running,
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
            status: UpdateStatus::Running,
            scheduled_datetime: schedule,
            run_datetime: Some(start),
            report_datetime: None,
            report: Some(report),
        };
        assert_eq!(event, ref_event);
    }
}
