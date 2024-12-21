// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

//! Report module for the Augeas module type.
//!
//! Structured reporting.

use anyhow::Error;
use rudder_module_type::Outcome;
use similar::{Algorithm, TextDiff, TextDiffConfig};
use std::time::Duration;

pub fn diff_config() -> TextDiffConfig {
    let mut c = TextDiff::configure();
    // We use the Myers algorithm, which is also the default in Git.
    // Patience is also a good choice, but it's slower.
    c.algorithm(Algorithm::Myers);
    // By safety, we limit the diff time.
    // This is a bit arbitrary, but we don't want to hang forever, and 1 second should
    // be more than enough.
    c.timeout(Duration::from_secs(1));
    c
}

/// Report a change in a file.
///
/// We don't repeat the parameters.
pub struct ChangeReport<'a> {
    pub outcome: Outcome,
    /// The unified diff of the change.
    ///
    /// <https://www.gnu.org/software/diffutils/manual/html_node/Detailed-Unified.html>
    pub diff: Option<TextDiff<'a, 'a, 'a, str>>,
    /// The error that occurred.
    pub error: Option<Error>,
}

impl ChangeReport<'_> {
    pub fn new(outcome: Outcome) -> Self {
        Self {
            outcome,
            diff: None,
            error: None,
        }
    }
}
