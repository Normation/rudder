// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use anyhow::{Result, anyhow};
use chrono::{DateTime, Utc};
use log::debug;
use serde::{Deserialize, Serialize};
use std::{
    fmt::Display,
    process::{Command, Output},
};

use crate::package_manager::PackageDiff;

/// Outcome of each function
///
/// We need to collect outputs for reporting, but also to log in live for debugging purposes.
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

    pub fn new_output(res: Result<T>, stdout: Vec<String>, stderr: Vec<String>) -> Self {
        Self {
            inner: res,
            stdout,
            stderr,
        }
    }

    /// Add logs to stdout
    pub fn stdout(&mut self, s: String) {
        self.stdout.push(s)
    }

    /// Add log lines to stdout
    pub fn stdout_lines(&mut self, s: Vec<String>) {
        for l in s {
            self.stdout(l)
        }
    }

    /// Add logs to stderr
    pub fn stderr(&mut self, s: String) {
        self.stderr.push(s)
    }

    /// Add log lines to stderr
    pub fn stderr_lines(&mut self, s: Vec<String>) {
        for l in s {
            self.stderr(l)
        }
    }

    /// Chain a `ResultOutput` to another
    pub fn step<S>(self, s: ResultOutput<S>) -> ResultOutput<S> {
        let mut res = ResultOutput::new(s.inner);

        for l in self.stderr {
            res.stderr.push(l)
        }
        for l in self.stdout {
            res.stdout.push(l)
        }

        for l in s.stderr {
            res.stderr.push(l)
        }
        for l in s.stdout {
            res.stdout.push(l)
        }
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

#[derive(Clone, Debug, PartialEq, Eq, Copy)]
pub enum CommandBehavior {
    /// Consider a code != 0 as an error
    FailOnErrorCode,
    /// Consider a command that could run as a success, regardless of the return code
    OkOnErrorCode,
}

#[derive(Clone, Debug, PartialEq, Eq, Copy)]
pub enum CommandCapture {
    /// Capture everything
    StdoutStderr,
    /// Only capture stderr
    Stderr,
}

impl ResultOutput<Output> {
    /// Run a command and return output
    pub fn command(
        mut c: Command,
        behavior: CommandBehavior,
        output_behavior: CommandCapture,
    ) -> Self {
        let output = c.output();
        let mut res = ResultOutput::new(output.map_err(|e| e.into()));

        if let Ok(ref o) = res.inner {
            let stdout_s = String::from_utf8_lossy(&o.stdout);
            debug!("stdout: {stdout_s}");
            if output_behavior == CommandCapture::StdoutStderr {
                res.stdout.push(stdout_s.to_string());
            }
            let stderr_s = String::from_utf8_lossy(&o.stderr);
            res.stderr.push(stderr_s.to_string());
            debug!("stderr: {stderr_s}");
            if behavior == CommandBehavior::FailOnErrorCode && !o.status.success() {
                res.inner = Err(match o.status.code() {
                    Some(code) => anyhow!("Command failed with code: {code}",),
                    None => anyhow!("Command terminated by signal"),
                });
            }
        };
        res
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
pub struct Report {
    pub software_updated: Vec<PackageDiff>,
    pub status: Status,
    pub output: String,
    #[serde(skip_serializing_if = "Report::error_is_empty")]
    pub errors: Option<String>,
}

impl Default for Report {
    fn default() -> Self {
        Self::new()
    }
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

    fn error_is_empty(err: &Option<String>) -> bool {
        match err {
            Some(e) => e.chars().all(|c| c.is_whitespace() || c.is_control()),
            None => true,
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
        if let Err(ref e) = res.inner {
            self.stderr(format!("{e:?}"));
        }
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
pub struct ScheduleReport {
    pub status: Status,
    pub date: DateTime<Utc>,
}

impl ScheduleReport {
    pub fn new(datetime: DateTime<Utc>) -> Self {
        Self {
            status: Status::Success,
            date: datetime,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn it_skips_empty_errors() {
        assert!(Report::error_is_empty(&None));
        assert!(Report::error_is_empty(&Some("".to_string())));
        assert!(Report::error_is_empty(&Some("  \n  \n".to_string())));
        assert!(Report::error_is_empty(&Some("\t  \n\r".to_string())));
        assert!(!Report::error_is_empty(&Some("\t  \na\r".to_string())));
    }
}
