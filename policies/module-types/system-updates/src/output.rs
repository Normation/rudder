// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use std::{
    fmt::Display,
    process::{Command, Output},
};

use anyhow::Result;
use chrono::{DateTime, Utc};
use log::debug;
use serde::{Deserialize, Serialize};

use crate::package_manager::PackageDiff;

/// Outcome of each function
///
/// We need to collect outputs for reporting, but  also to log in live for debugging purposes.
#[must_use]
pub struct ResultOutput<T> {
    pub inner: Result<T>,
    pub stdout: Vec<String>,
    pub stderr: Vec<String>,
}

impl<T> ResultOutput<T> {
    pub fn new(res: Result<T>) -> Self {
        Self {
            inner: res,
            stdout: vec![],
            stderr: vec![],
        }
    }

    /// Add logs to stdout
    pub fn stdout(&mut self, s: String) {
        self.stdout.push(s)
    }

    /// Add logs to stderr
    pub fn stderr(&mut self, s: String) {
        self.stderr.push(s)
    }

    /// Chain a `ResultOutput` to another
    pub fn step(&mut self, res: ResultOutput<T>) {
        for l in res.stderr {
            self.stderr.push(l)
        }
        for l in res.stdout {
            self.stdout.push(l)
        }
        self.inner = res.inner;
    }
}

impl<T, S> ResultOutput<T> {
    pub fn result(self, res: Result<S>) -> ResultOutput<S> {
        Self {
            inner: res,
            stdout: self.stdout,
            stderr: self.stderr,
        }
    }
}

impl ResultOutput<Output> {
    /// Run a command and return output
    pub fn command(mut c: Command) -> Self {
        let output = c.output();
        let mut res = ResultOutput::new(output.map_err(|e| e.into()));

        // FIXME review
        if let Ok(ref o) = res.inner {
            let stdout_s = String::from_utf8_lossy(&o.stdout);
            res.stdout.push(stdout_s.to_string());
            debug!("stdout: {stdout_s}");
            let stderr_s = String::from_utf8_lossy(&o.stderr);
            res.stderr.push(stderr_s.to_string());
            debug!("stderr: {stderr_s}");
        };
        res
    }

    pub fn clear_ok(self) -> ResultOutput<()> {
        let mut n = ResultOutput::new(Ok(()));

        if let Err(e) = self.inner {
            n.inner = Err(e)
        }
        n.stdout = self.stdout;
        n.stderr = self.stderr;
        n
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize, Copy)]
#[serde(rename_all = "kebab-case")]
pub enum Status {
    Error,
    Success,
    Repaired,
    Scheduled,
}

impl Display for Status {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Status::Error => write!(f, "error"),
            Status::Success => write!(f, "success"),
            Status::Repaired => write!(f, "repaired"),
            Status::Scheduled => write!(f, "scheduled"),
        }
    }
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
            // We did nothing, but successfully
            status: Status::Success,
            output: "".to_string(),
            errors: None,
        }
    }

    pub fn is_err(&self) -> bool {
        self.status == Status::Error
    }

    /// Add log lines to stdout
    pub fn stdout_lines<T: AsRef<str>>(&mut self, s: &[T]) {
        for l in s {
            self.stdout(l)
        }
    }

    /// Add a log line to stdout
    pub fn stdout<T: AsRef<str>>(&mut self, s: T) {
        let s = s.as_ref();
        self.output.push('\n');
        debug!("stdout: {s}");
        self.output.push_str(s)
    }

    /// Add log lines to stderr
    pub fn stderr_lines<T: AsRef<str>>(&mut self, s: &[T]) {
        if s.is_empty() {
            return;
        }
        for l in s {
            self.stderr(l)
        }
    }

    /// Add a log line to stderr
    pub fn stderr<T: AsRef<str>>(&mut self, s: T) {
        let s = s.as_ref();

        if self.errors.is_none() {
            self.errors = Some(String::new());
        }
        self.errors.as_mut().unwrap().push('\n');
        debug!("stderr: {s}");
        self.errors.as_mut().unwrap().push_str(s.as_ref())
    }

    pub fn diff(&mut self, diff: Vec<PackageDiff>) {
        if !self.is_err() && !diff.is_empty() {
            self.status = Status::Repaired
        }
        self.software_updated = diff;
    }

    /// Chain a `ResultOutput` to the report
    pub fn step<T>(&mut self, res: ResultOutput<T>) {
        self.stdout_lines(res.stdout.as_slice());
        self.stderr_lines(res.stderr.as_slice());
        self.status = match (self.status, res.inner.is_ok()) {
            (Status::Error, _) => Status::Error,
            (_, false) => Status::Error,
            (s, true) => s,
        }
    }
}

/// Report before the start datetime. It is sent to the web application for user information.
///
/// Same as the Python implementation in 8.1.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub(crate) struct ScheduleReport {
    pub status: Status,
    pub date: DateTime<Utc>,
}

impl ScheduleReport {
    pub fn new(datetime: DateTime<Utc>) -> Self {
        Self {
            status: Status::Error,
            date: datetime,
        }
    }
}
