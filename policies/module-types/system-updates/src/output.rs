// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use crate::package_manager::PackageDiff;
use anyhow::{anyhow, Error, Result};
use log::debug;
use serde::{Deserialize, Serialize};
use std::process::{Command, Output};

/// Outcome of each function
///
/// We need to collect outputs for reporting
#[must_use]
pub struct ResultOutput<T> {
    pub res: Result<T>,
    pub stdout: Vec<String>,
    pub stderr: Vec<String>,
}

impl<T> ResultOutput<T> {
    pub fn new(res: Result<T>) -> Self {
        Self {
            res,
            stdout: vec![],
            stderr: vec![],
        }
    }

    pub fn stdout(&mut self, s: String) {
        self.stdout.push(s)
    }

    pub fn stderr(&mut self, s: String) {
        self.stderr.push(s)
    }

    /// Returns stdout
    pub fn command(&mut self, mut c: Command) -> Result<Output> {
        let res = c.output();

        if let Ok(ref o) = res {
            let stdout_s = String::from_utf8_lossy(&*o.stdout);
            self.stdout.push(stdout_s.to_string());
            debug!("stdout: {stdout_s}");
            let stderr_s = String::from_utf8_lossy(&*o.stderr);
            self.stderr.push(stderr_s.to_string());
            debug!("stderr: {stderr_s}");
        };

        res.map_err(|e| e.into())
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize, Copy)]
#[serde(rename_all = "kebab-case")]
pub enum Status {
    Error,
    Success,
    Repaired,
}

// Same as the Python implementation in 8.1.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub(crate) struct Report {
    pub software_updated: Vec<PackageDiff>,
    pub status: Status,
    pub output: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub errors: Option<String>,
}

impl Report {
    pub fn new() -> Self {
        Self {
            software_updated: vec![],
            status: Status::Error,
            output: "".to_string(),
            errors: None,
        }
    }
}
