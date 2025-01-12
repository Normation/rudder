// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

//! Report module for the Augeas module type.
//!
//! Structured reporting.

use anyhow::Error;
use rudder_module_type::Outcome;
use similar::udiff::unified_diff;
use similar::Algorithm;

/// Compute the unified diff between two strings.
///
/// TODO: Use structured diff?
pub fn diff(a: &str, b: &str) -> String {
    unified_diff(Algorithm::Myers, a, b, 3, None)
}

/// Report a change in a file.
///
/// We don't repeat the parameters.
pub struct ChangeReport {
    pub outcome: Outcome,
    /// The unified diff of the change.
    ///
    /// <https://www.gnu.org/software/diffutils/manual/html_node/Detailed-Unified.html>
    pub diff: Option<String>,
    /// The error that occurred.
    pub error: Option<Error>,
}

impl ChangeReport {
    pub fn new(outcome: Outcome) -> Self {
        Self {
            outcome,
            diff: None,
            error: None,
        }
    }
}
