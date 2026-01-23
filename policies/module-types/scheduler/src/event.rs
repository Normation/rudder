// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

use crate::db::EventDatabase;
use chrono::{DateTime, SecondsFormat, Utc};
use serde::{Deserialize, Serialize};
use std::fmt::{Display, Formatter};

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum EventSchedule {
    /// Run once as soon as possible when conditions are met
    Once,
    /// Run once, uniformly splayed between not_before and not_after
    ///
    /// WARNING: if not_before and not_after are not set, this never runs
    OnceSplayed,
    /// Run always when conditions are met
    Always,
    /// Never run
    Never,
}

impl Display for EventSchedule {
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        let s = match self {
            EventSchedule::Once => "once",
            EventSchedule::OnceSplayed => "once_splayed",
            EventSchedule::Always => "always",
            EventSchedule::Never => "never",
        };
        f.write_str(s)
    }
}

/// Information about an event stored in the database
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub struct Event {
    pub(crate) id: String,
    pub(crate) schedule_id: String,
    pub(crate) name: String,
    // No need to have an enum here, as the type is only informative
    // Keep it simple and extensible
    pub(crate) e_type: String,
    pub(crate) schedule: EventSchedule,
    pub(crate) created: DateTime<Utc>,
    pub(crate) not_before: Option<DateTime<Utc>>,
    pub(crate) not_after: Option<DateTime<Utc>>,
    // None if not started yet
    pub(crate) datetime: Option<DateTime<Utc>>,
}

impl Event {
    pub fn new(
        id: String,
        schedule_id: String,
        name: String,
        e_type: String,
        schedule: EventSchedule,
        not_before: Option<DateTime<Utc>>,
        not_after: Option<DateTime<Utc>>,
    ) -> Self {
        Self {
            id,
            schedule_id,
            name,
            e_type,
            schedule,
            created: Utc::now(),
            not_before,
            not_after,
            datetime: None,
        }
    }

    /// Schedule the event, returns the target datetime if scheduled
    pub fn schedule(&mut self) -> Option<DateTime<Utc>> {
        match self.schedule {
            EventSchedule::Once => {
                let now = Utc::now();
                self.datetime = Some(now);
                Some(now)
            }
            EventSchedule::OnceSplayed => {
                if let (Some(nb), Some(na)) = (self.not_before, self.not_after) {
                    todo!()
                } else {
                    // Cannot schedule
                    None
                }
            }
            EventSchedule::Always => {
                let now = Utc::now();
                self.datetime = Some(now);
                Some(now)
            }
            EventSchedule::Never => None,
        }
    }

    pub fn has_run(&self) -> bool {
        self.datetime.is_some()
    }

    // Run asap
    pub fn should_run(&self, now: DateTime<Utc>) -> bool {
        if let Some(nb) = self.not_before {
            if now < nb {
                return false;
            }
        }
        if let Some(na) = self.not_after {
            if now > na {
                return false;
            }
        }
        true
    }
}

impl Display for Event {
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        f.write_str(&format!("\nEvent ID: {}\n", self.id))?;
        f.write_str(&format!("\nSchedule ID: {}\n", self.schedule_id))?;
        f.write_str(&format!("Type: {}\n", self.e_type))?;
        f.write_str(&format!("Name: {}\n", self.name))?;
        f.write_str(&format!("Schedule: {}\n", self.schedule))?;
        f.write_str(&format!(
            "Created: {}\n",
            self.created.to_rfc3339_opts(SecondsFormat::Secs, true)
        ))?;
        if let Some(d) = self.datetime {
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
        Ok(())
    }
}

// todo
//
// on prend une liste d'events en input, on extrait les events de la base et on reconcilie

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Events {
    events: Vec<Event>,
}

impl Events {
    pub fn new(events: Vec<Event>) -> Self {
        Self { events }
    }

    pub fn update_events(&mut self, db: &mut EventDatabase) -> anyhow::Result<()> {
        db.cancel_other_events(
            self.events
                .iter()
                .map(|e| e.id.as_str())
                .collect::<Vec<&str>>()
                .as_slice(),
        )?;
        Ok(())
    }
}
