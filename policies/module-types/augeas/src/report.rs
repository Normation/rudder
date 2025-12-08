// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

//! Report module for the Augeas module type.
//!
//! Structured reporting.
//! For now, it is serialized as a human-readable string
//! to be sent to the agent for reporting.
//!
//! There are two ways to report about a non-compliance:
//!
//! * "compiler error-like" message pointing to the problem.
//! * "diff-like" message showing the difference between the expected, and the actual state.
//!
//! We prioritize the first one whenever possible, as it is more user-friendly.

use crate::{CRATE_NAME, CRATE_VERSION};
use chrono::{DateTime, Utc};
use serde::Serialize;
use std::{fmt::Display, sync::LazyLock};

static SOURCE_NAME: LazyLock<String> = LazyLock::new(|| format!("{CRATE_NAME}/{CRATE_VERSION}"));

#[derive(Debug, PartialEq, Eq, Copy, Clone, Serialize)]
/// The outcome of a module call.
pub enum Outcome {
    Success,
    NonCompliant,
    Failure,
}

impl Display for Outcome {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Outcome::Success => write!(f, "Success"),
            Outcome::NonCompliant => write!(f, "NonCompliant"),
            Outcome::Failure => write!(f, "Failure"),
        }
    }
}

#[derive(Debug, Clone, Serialize)]
/// Report a change in a file.
///
/// We don't repeat the parameters.
pub struct Report<'a> {
    // FIXME: mettre les inputs dans le report
    // comme ansible
    pub report_id: &'a str,
    pub source: &'static String,
    pub outcome: Outcome,
    /// The non-compliances that occurred.
    pub non_compliances: Vec<String>,
    /// The errors that occurred.
    pub errors: Vec<String>,
    /// Events that happened to files. Only one event can happen in this module.
    pub events: Vec<Event>,
}

impl<'a> Report<'a> {
    pub fn new(outcome: Outcome, report_id: &'a str) -> Self {
        Self {
            source: &*SOURCE_NAME,
            report_id,
            outcome,
            non_compliances: vec![],
            errors: vec![],
            events: vec![],
        }
    }

    pub fn human_format(&self) -> String {
        let mut s = format!(
            "Report ID: {}\nSource: {}\nOutcome: {}\n",
            self.report_id, self.source, self.outcome
        );

        for event in &self.events {
            s.push_str(&format!(
                "Event: {}\nPath: {}\nTimestamp: {}\nMessage: {}\n",
                match event.type_ {
                    FileEventType::Created => "Created",
                    FileEventType::Modified => "Modified",
                    FileEventType::Deleted => "Deleted",
                },
                event.path,
                event.timestamp,
                event.message,
            ));

            if let Some(diff) = &event.diff {
                s.push_str(&format!("Diff:\n{diff}\n"));
            }
        }

        for non_compliance in &self.non_compliances {
            s.push_str(&format!("Non-compliance: {non_compliance}\n"));
        }

        for error in &self.errors {
            s.push_str(&format!("Error: {error}\n"));
        }

        s
    }
}

#[derive(Debug, Clone, Serialize)]
pub enum FileEventType {
    Created,
    Modified,
    Deleted,
}

#[derive(Debug, Clone, Serialize)]
/// A file event.
///
/// An event is something that happened to a file.
pub struct Event {
    #[serde(rename = "type")]
    pub type_: FileEventType,
    pub path: String,
    pub timestamp: DateTime<Utc>,
    /// The unified diff of the change.
    ///
    /// <https://www.gnu.org/software/diffutils/manual/html_node/Detailed-Unified.html>
    pub diff: Option<String>,
    /// Explanation of the event.
    pub message: String,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_report_human_format() {
        let report = Report {
            report_id: "123",
            source: &SOURCE_NAME,
            outcome: Outcome::NonCompliant,
            non_compliances: vec!["foo".to_string()],
            errors: vec!["bar".to_string()],
            events: vec![Event {
                type_: FileEventType::Created,
                path: "".to_string(),
                timestamp: Default::default(),
                diff: None,
                message: "".to_string(),
            }],
        }
        .human_format();
        println!("{report}");
        // FIXME add assertions
    }
}
