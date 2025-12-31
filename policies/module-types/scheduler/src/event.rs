// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

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

/// Information about an event stored in the database
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub struct Event {
    pub(crate) id: String,
    pub(crate) name: String,
    // No need to have an enum here, as the type is only informative
    // Keep it simple and extensible
    pub(crate) event_type: String,
    pub(crate) schedule: EventSchedule,
    pub(crate) created: DateTime<Utc>,
    pub(crate) not_before: Option<DateTime<Utc>>,
    pub(crate) not_after: Option<DateTime<Utc>>,
    // None if not started yet
    pub(crate) datetime: Option<DateTime<Utc>>,
}

impl Event {
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
        f.write_str(&format!("Name: {}\n", self.name))?;
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
