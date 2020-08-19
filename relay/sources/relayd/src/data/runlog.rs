// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use crate::{
    data::{
        report::{runlog, RawReport},
        Report, RunInfo,
    },
    error::Error,
    output::database::schema::reportsexecution,
};
use chrono::prelude::*;
use serde::{Deserialize, Serialize};
use std::{
    collections::HashSet,
    convert::TryFrom,
    fmt::{self, Display},
    fs::read_to_string,
    path::Path,
    str::FromStr,
};

use tracing::{debug, error, warn};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Insertable)]
#[table_name = "reportsexecution"]
/// Represents a runlog in the database
pub struct InsertedRunlog {
    #[column_name = "nodeid"]
    pub node_id: String,
    #[column_name = "date"]
    pub date: DateTime<FixedOffset>,
    #[column_name = "complete"]
    pub complete: bool,
    #[column_name = "nodeconfigid"]
    pub node_config_id: String,
    #[column_name = "insertionid"]
    pub insertion_id: i64,
    #[column_name = "insertiondate"]
    pub insertion_date: Option<DateTime<FixedOffset>>,
    #[column_name = "compliancecomputationdate"]
    pub compliance_computation_date: Option<DateTime<FixedOffset>>,
}

impl InsertedRunlog {
    pub fn new(runlog: &RunLog, insertion_id: i64) -> Self {
        Self {
            node_id: runlog.info.node_id.clone(),
            date: runlog.info.timestamp,
            complete: true,
            node_config_id: runlog.config_id.clone(),
            insertion_id,
            // None means default value will be inserted, here current_timestamp
            insertion_date: None,
            compliance_computation_date: None,
        }
    }
}

#[derive(Debug, Serialize, Deserialize, PartialEq, Eq)]
pub struct RunLog {
    pub info: RunInfo,
    pub config_id: String,
    // Never empty vec
    pub reports: Vec<Report>,
}

impl Display for RunLog {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        for report in &self.reports {
            writeln!(f, "R: {:}", report)?
        }
        Ok(())
    }
}

impl RunLog {
    /// Mainly used for testing
    pub fn new<P: AsRef<Path>>(path: P) -> Result<Self, Error> {
        let info = RunInfo::from_str(
            path.as_ref()
                .file_name()
                .and_then(|r| r.to_str())
                .ok_or_else(|| {
                    Error::InvalidRunInfo(path.as_ref().to_str().unwrap_or("").to_string())
                })?,
        )?;
        RunLog::try_from((info, read_to_string(path)?.as_ref()))
    }

    pub fn without_types(&self, types: &HashSet<String>) -> Self {
        Self {
            info: self.info.clone(),
            config_id: self.config_id.clone(),
            reports: self
                .reports
                .as_slice()
                .iter()
                .filter(|r| !types.contains(&r.event_type))
                .cloned()
                .collect(),
        }
    }
}

impl TryFrom<(RunInfo, &str)> for RunLog {
    type Error = Error;

    fn try_from(raw_reports: (RunInfo, &str)) -> Result<Self, Self::Error> {
        match runlog(raw_reports.1) {
            Ok(raw_runlog) => {
                debug!("Parsed runlog {:#?}", raw_runlog.1);
                let (reports, failed): (Vec<_>, Vec<_>) =
                    raw_runlog.1.into_iter().partition(Result::is_ok);
                for invalid_report in failed.into_iter().map(Result::unwrap_err) {
                    warn!("Invalid report: {}", invalid_report);
                }

                let reports: Vec<RawReport> = reports.into_iter().map(Result::unwrap).collect();
                RunLog::try_from((raw_reports.0, reports))
            }
            Err(e) => {
                warn!("{:?}: could not parse '{}'", e, raw_reports.0);
                Err(Error::InvalidRunLog(format!("{:?}", e)))
            }
        }
    }
}

impl TryFrom<(RunInfo, Vec<RawReport>)> for RunLog {
    type Error = Error;

    fn try_from(raw_reports: (RunInfo, Vec<RawReport>)) -> Result<Self, Self::Error> {
        let reports: Vec<Report> = raw_reports
            .1
            .into_iter()
            .flat_map(RawReport::into_reports)
            .collect();
        let info = raw_reports.0;
        let timestamp = reports
            .first()
            .ok_or(Error::InconsistentRunlog)?
            .start_datetime;

        let config_id = reports
            .iter()
            .find(|r| r.event_type == "control" && r.component == "end")
            .ok_or(Error::MissingEndRun)?
            .key_value
            .clone();

        for report in &reports {
            if info.node_id != report.node_id {
                error!(
                    "Wrong node id in report {:#?}, got {} but should be {}",
                    report, report.node_id, info.node_id
                );
                return Err(Error::InconsistentRunlog);
            }
            if timestamp != report.start_datetime {
                warn!(
                    "Wrong execution timestamp in report {:#?}, got {} but should be {}",
                    report, report.start_datetime, timestamp
                );
            }
        }
        Ok(Self {
            info,
            reports,
            config_id,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::DateTime;
    use std::fs::read_dir;

    #[test]
    fn it_parses_runlog() {
        // For each .json file, compare it with the matching .log
        let mut test_done = 0;
        for entry in read_dir("tests/files/runlogs/").unwrap() {
            let path = entry.unwrap().path();
            if path.extension().unwrap() == "json" {
                let runlog = RunLog::new(&path.with_extension("log")).unwrap();
                //println!("{}", serde_json::to_string_pretty(&runlog).unwrap());
                let reference: RunLog =
                    serde_json::from_str(&read_to_string(path).unwrap()).unwrap();
                assert_eq!(runlog, reference);
                test_done += 1;
            }
        }
        // check we did at least one test
        assert!(test_done > 1);
    }

    #[test]
    fn it_detect_invalid_node_in_runlog() {
        assert!(
            RunLog::new("2018-08-24T15:55:01+00:00@e745a140-40bc-4b86-b6dc-084488fc906c.log")
                .is_err()
        );
    }

    #[test]
    fn it_removes_logs_in_runlog() {
        let mut filter = HashSet::new();
        let _ = filter.insert("log_info".to_string());
        let end_run = Report {
            start_datetime: DateTime::parse_from_str(
                "2018-08-24 15:55:01+00:00",
                "%Y-%m-%d %H:%M:%S%z",
            )
            .unwrap(),
            rule_id: "rudder".into(),
            directive_id: "run".into(),
            component: "CRON Daemon".into(),
            key_value: "20180824-130007-3ad37587".into(),
            event_type: "control".into(),
            msg: "End execution".into(),
            policy: "Common".into(),
            node_id: "root".into(),
            serial: 0,
            execution_datetime: DateTime::parse_from_str(
                "2018-08-24 15:55:01+00:00",
                "%Y-%m-%d %H:%M:%S%z",
            )
            .unwrap(),
        };
        assert_eq!(
            RunLog {
                info: RunInfo::from_str(
                    "2018-08-24T15:55:01+00:00@e745a140-40bc-4b86-b6dc-084488fc906b.log"
                )
                .unwrap(),
                config_id: "20180824-130007-3ad37587".to_string(),
                reports: vec![
                    Report {
                        start_datetime: DateTime::parse_from_str(
                            "2018-08-24 15:55:01+00:00",
                            "%Y-%m-%d %H:%M:%S%z"
                        )
                        .unwrap(),
                        rule_id: "hasPolicyServer-root".into(),
                        directive_id: "common-root".into(),
                        component: "CRON Daemon".into(),
                        key_value: "None".into(),
                        event_type: "result_repaired".into(),
                        msg: "Cron daemon status was repaired".into(),
                        policy: "Common".into(),
                        node_id: "root".into(),
                        serial: 0,
                        execution_datetime: DateTime::parse_from_str(
                            "2018-08-24 15:55:01+00:00",
                            "%Y-%m-%d %H:%M:%S%z"
                        )
                        .unwrap(),
                    },
                    Report {
                        start_datetime: DateTime::parse_from_str(
                            "2018-08-24 15:55:01+00:00",
                            "%Y-%m-%d %H:%M:%S%z"
                        )
                        .unwrap(),
                        rule_id: "hasPolicyServer-root".into(),
                        directive_id: "common-root".into(),
                        component: "CRON Daemon".into(),
                        key_value: "None".into(),
                        event_type: "log_info".into(),
                        msg: "Cron daemon status was repaired".into(),
                        policy: "Common".into(),
                        node_id: "root".into(),
                        serial: 0,
                        execution_datetime: DateTime::parse_from_str(
                            "2018-08-24 15:55:01+00:00",
                            "%Y-%m-%d %H:%M:%S%z"
                        )
                        .unwrap(),
                    },
                    end_run.clone()
                ]
            }
            .without_types(&filter),
            RunLog {
                info: RunInfo::from_str(
                    "2018-08-24T15:55:01+00:00@e745a140-40bc-4b86-b6dc-084488fc906b.log"
                )
                .unwrap(),
                config_id: "20180824-130007-3ad37587".to_string(),
                reports: vec![
                    Report {
                        start_datetime: DateTime::parse_from_str(
                            "2018-08-24 15:55:01+00:00",
                            "%Y-%m-%d %H:%M:%S%z"
                        )
                        .unwrap(),
                        rule_id: "hasPolicyServer-root".into(),
                        directive_id: "common-root".into(),
                        component: "CRON Daemon".into(),
                        key_value: "None".into(),
                        event_type: "result_repaired".into(),
                        msg: "Cron daemon status was repaired".into(),
                        policy: "Common".into(),
                        node_id: "root".into(),
                        serial: 0,
                        execution_datetime: DateTime::parse_from_str(
                            "2018-08-24 15:55:01+00:00",
                            "%Y-%m-%d %H:%M:%S%z"
                        )
                        .unwrap(),
                    },
                    end_run
                ]
            }
        );
    }
}
