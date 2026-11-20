// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use crate::package_manager::{PackageAction, PackageDiff, PackageId};
use anyhow::{Result, anyhow};
use chrono::{DateTime, Utc};
use log::debug;
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};
use std::{
    fmt::Display,
    process::{Command, Output},
};

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

    pub fn into_err<E>(self, e: E) -> ResultOutput<T>
    where
        E: Into<anyhow::Error> + std::fmt::Debug,
    {
        let mut stderr = self.stderr;
        stderr.push(format!("{:#?}", e));
        ResultOutput {
            inner: Err(e.into()),
            stdout: self.stdout,
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
    pub fn step<S>(self, next: ResultOutput<S>) -> ResultOutput<S> {
        let mut res = ResultOutput::new(next.inner);

        for l in self.stderr {
            res.stderr.push(l)
        }
        for l in self.stdout {
            res.stdout.push(l)
        }

        for l in next.stderr {
            res.stderr.push(l)
        }
        for l in next.stdout {
            res.stdout.push(l)
        }
        res
    }

    pub fn log_step<S>(&mut self, next: &ResultOutput<S>) {
        for l in &next.stderr {
            self.stderr(l.clone())
        }
        for l in &next.stdout {
            self.stdout(l.clone())
        }
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

    pub fn clear_ok_with_details(self) -> ResultOutput<Option<HashMap<PackageId, String>>> {
        let mut n = ResultOutput::new(Ok(None));
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

        res.stdout.push(format!(
            "cmd: {} {}",
            c.get_program().to_string_lossy(),
            c.get_args()
                .map(|a| format!("'{}'", a.to_string_lossy()))
                .collect::<Vec<String>>()
                .join(" "),
        ));
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

    pub fn diff(&mut self, diff: Vec<PackageDiff>, details: Option<HashMap<PackageId, String>>) {
        if !self.is_err() && !diff.is_empty() {
            self.status = Status::Repaired
        }
        self.software_updated = match details {
            None => diff,
            Some(d) => {
                // Add details if any to the packages already listed in the diff
                let mut result: Vec<PackageDiff> = diff
                    .into_iter()
                    .map(|x| PackageDiff {
                        details: d.get(&x.id).cloned(),
                        ..x
                    })
                    .collect();
                // Add packages listed in the details, but not already listed in the diff
                let packages_names: HashSet<PackageId> =
                    result.iter().map(|x| x.id.clone()).collect();
                result.extend(
                    d.into_iter()
                        .filter(|(k, _)| !packages_names.contains(k))
                        .map(|(id, log_details)| PackageDiff {
                            id,
                            old_version: None,
                            new_version: None,
                            action: PackageAction::PendingInstall,
                            details: Some(log_details),
                        }),
                );
                result
            }
        }
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

/// Check the reports against the format specified in `REPORTING.md`.
#[cfg(test)]
mod schema_tests {
    use super::*;
    use crate::package_manager::{PackageInfo, PackageList, PackageManager};
    use chrono::TimeZone;
    use jsonschema::Validator;
    use serde_json::{Value, json};

    const SCHEMA: &str = include_str!("../reporting.schema.json");

    /// Validator for one of the definitions of the schema.
    fn validator(definition: &str) -> Validator {
        let mut schema: Value = serde_json::from_str(SCHEMA).unwrap();
        let root = schema.as_object_mut().unwrap();
        root.remove("anyOf");
        root.insert("$ref".to_string(), json!(format!("#/$defs/{definition}")));
        jsonschema::options()
            .should_validate_formats(true)
            .build(&schema)
            .unwrap()
    }

    fn assert_valid(definition: &str, value: &Value) {
        let errors: Vec<String> = validator(definition)
            .iter_errors(value)
            .map(|e| e.to_string())
            .collect();
        assert!(
            errors.is_empty(),
            "{value} should be a valid {definition} report: {errors:#?}"
        );
    }

    fn package_list(packages: &[(&str, &str, &str)]) -> PackageList {
        PackageList::new(
            packages
                .iter()
                .map(|(name, arch, version)| {
                    (
                        PackageId::new(name.to_string(), arch.to_string()),
                        PackageInfo {
                            version: version.to_string(),
                            from: "".to_string(),
                            source: PackageManager::Yum,
                            details: None,
                        },
                    )
                })
                .collect(),
        )
    }

    /// A diff with one package of each action.
    fn diff() -> Vec<PackageDiff> {
        let before = package_list(&[
            ("xz", "x86_64", "5.2.4-3.el8"),
            ("dbus-common", "noarch", "1:1.12.8-18.el8"),
            ("telnet", "x86_64", "1:0.17-76.el8"),
        ]);
        let after = package_list(&[
            ("xz", "x86_64", "5.2.4-4.el8"),
            ("dbus-common", "noarch", "1:1.12.8-18.el8.1"),
            ("kernel-core", "x86_64", "4.18.0-553.el8"),
        ]);
        before.diff(after)
    }

    #[test]
    fn schema_matches_both_reports() {
        let schema: Value = serde_json::from_str(SCHEMA).unwrap();
        let validator = jsonschema::options()
            .should_validate_formats(true)
            .build(&schema)
            .unwrap();
        let date = Utc.with_ymd_and_hms(2026, 9, 30, 22, 41, 17).unwrap();
        assert!(validator.is_valid(&serde_json::to_value(ScheduleReport::new(date)).unwrap()));
        assert!(validator.is_valid(&serde_json::to_value(Report::new()).unwrap()));
    }

    #[test]
    fn schedule_report_is_valid() {
        let date = Utc.with_ymd_and_hms(2026, 9, 30, 22, 41, 17).unwrap();
        let report = serde_json::to_value(ScheduleReport::new(date)).unwrap();
        assert_eq!(
            report,
            json!({"status": "success", "date": "2026-09-30T22:41:17Z"})
        );
        assert_valid("schedule", &report);
    }

    #[test]
    fn schedule_report_with_subsecond_date_is_valid() {
        // Immediate events use the current time, with a fractional part
        let report = serde_json::to_value(ScheduleReport::new(Utc::now())).unwrap();
        assert_valid("schedule", &report);
    }

    #[test]
    fn empty_report_is_valid() {
        let report = serde_json::to_value(Report::new()).unwrap();
        assert_eq!(
            report,
            json!({"software-updated": [], "status": "success", "output": ""})
        );
        assert_valid("update", &report);
    }

    #[test]
    fn repaired_report_is_valid() {
        let mut report = Report::new();
        report.stdout("cmd: yum '-y' 'update'");
        report.diff(diff(), None);
        assert_eq!(report.status, Status::Repaired);

        let report = serde_json::to_value(report).unwrap();
        let actions: Vec<&str> = report["software-updated"]
            .as_array()
            .unwrap()
            .iter()
            .map(|p| p["action"].as_str().unwrap())
            .collect();
        for action in ["added", "updated", "removed"] {
            assert!(actions.contains(&action), "missing {action} in {report}");
        }
        assert_valid("update", &report);
    }

    #[test]
    fn report_with_details_is_valid() {
        let details = HashMap::from([(
            PackageId::new("kernel-core".to_string(), "x86_64".to_string()),
            "\nDownload result:\n  - result_code: 2, HRESULT 0x00000000".to_string(),
        )]);
        let mut report = Report::new();
        report.diff(diff(), Some(details));

        let report = serde_json::to_value(report).unwrap();
        assert!(
            report["software-updated"]
                .as_array()
                .unwrap()
                .iter()
                .any(|p| p.get("details").is_some())
        );
        assert_valid("update", &report);
    }

    /// Updates with details but absent from the diff, e.g. waiting for a reboot.
    fn pending_details() -> HashMap<PackageId, String> {
        HashMap::from([(
            PackageId::new(
                "2026-09 Cumulative Update (KB5065432)".to_string(),
                "noarch".to_string(),
            ),
            "\nDownload result:\n  - result_code: 2, HRESULT 0x00000000\nInstall result:\n  - result_code: 2, HRESULT 0x00000000, reboot_required: true".to_string(),
        )])
    }

    #[test]
    fn report_with_pending_install_is_valid() {
        let mut report = Report::new();
        report.diff(diff(), Some(pending_details()));
        assert_eq!(report.status, Status::Repaired);

        let report = serde_json::to_value(report).unwrap();
        let pending: Vec<&Value> = report["software-updated"]
            .as_array()
            .unwrap()
            .iter()
            .filter(|p| p["action"] == "pending-install")
            .collect();
        assert_eq!(pending.len(), 1, "{report}");
        assert_valid("update", &report);
    }

    #[test]
    fn report_with_only_pending_install_is_success() {
        let mut report = Report::new();
        report.diff(vec![], Some(pending_details()));
        // Pending installs are not changes, see REPORTING.md
        assert_eq!(report.status, Status::Success);

        let report = serde_json::to_value(report).unwrap();
        assert_eq!(report["software-updated"].as_array().unwrap().len(), 1);
        assert_valid("update", &report);
    }

    #[test]
    fn error_report_is_valid() {
        let mut report = Report::new();
        report.step(ResultOutput::<()>::new_output(
            Err(anyhow!("Command failed with code: 1")),
            vec!["cmd: yum '-y' 'update'".to_string()],
            vec!["Error: Failed to download metadata".to_string()],
        ));
        report.stderr("Pre-run hooks failed, aborting upgrade");
        assert_eq!(report.status, Status::Error);

        let report = serde_json::to_value(report).unwrap();
        assert!(report["errors"].is_string());
        assert_valid("update", &report);
    }

    #[test]
    fn report_with_blank_errors_omits_them() {
        let mut report = Report::new();
        report.stderr("  ");

        let report = serde_json::to_value(report).unwrap();
        assert!(report.get("errors").is_none());
        assert_valid("update", &report);
    }

    #[test]
    fn report_round_trips() {
        let mut report = Report::new();
        report.stdout("output");
        report.stderr("error");
        report.diff(diff(), None);

        let value = serde_json::to_value(&report).unwrap();
        assert_valid("update", &value);
        assert_eq!(serde_json::from_value::<Report>(value).unwrap(), report);
    }
}
