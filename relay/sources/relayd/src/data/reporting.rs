use crate::{data::nodes::NodeId, error::Error, output::database::schema::rudder_sys_events};
use chrono::prelude::*;
use lazy_static::lazy_static;
use regex::Regex;
use serde::{Deserialize, Serialize};
use slog::{slog_debug, slog_trace, slog_warn};
use slog_scope::{debug, trace, warn};
use std::{
    fmt::{self, Display},
    str::FromStr,
};

#[derive(Debug, Serialize, Deserialize, PartialEq, Eq, Insertable)]
#[table_name = "rudder_sys_events"]
pub struct Report {
    #[column_name = "executionDate"]
    pub start_datetime: DateTime<FixedOffset>,
    #[column_name = "ruleId"]
    pub rule_id: String,
    #[column_name = "directiveId"]
    pub directive_id: String,
    pub component: String,
    #[column_name = "keyValue"]
    pub key_value: String,
    #[column_name = "eventType"]
    pub event_type: String,
    #[column_name = "msg"]
    pub msg: String,
    #[column_name = "policy"]
    pub policy: String,
    #[column_name = "nodeId"]
    pub node_id: NodeId,
    #[column_name = "executionTimeStamp"]
    pub execution_datetime: DateTime<FixedOffset>,
    pub serial: i32,
}

#[derive(Debug, Serialize, Deserialize, PartialEq, Eq)]
pub struct RunInfo {
    pub node_id: NodeId,
    pub timestamp: DateTime<FixedOffset>,
}

impl Display for Report {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(
            f,
            "@@{:}@@{:}@@{:}@@{:}@@{:}@@{:}@@{:}@@{:}##{:}@#{:}",
            self.policy,
            self.event_type,
            self.rule_id,
            self.directive_id,
            self.serial,
            self.component,
            self.key_value,
            self.start_datetime,
            self.node_id,
            self.msg
        )
    }
}

impl Display for RunLog {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        for report in &self.reports {
            writeln!(f, "R: {:}", report)?
        }

        Ok(())
    }
}

impl FromStr for RunInfo {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        lazy_static! {
            static ref RUNINFO: Regex = Regex::new(r"^(?P<timestamp>.+)@(?P<node_id>.+)\.log$")
                .expect("Invalid report parsing regex");
        }
        trace!("Parsing report: '{}'", s; "component" => "parser");

        let cap = match RUNINFO.captures(s) {
            None => {
                warn!("Could not parse run info: '{}'", s; "component" => "parser");
                return Err(Error::InvalidReport);
            }
            Some(capture) => capture,
        };

        let report = RunInfo {
            node_id: cap["node_id"].into(),
            timestamp: DateTime::parse_from_str(&cap["timestamp"], "%+")?,
        };
        debug!("Parsed run info {:#?}", report; "component" => "parser");
        Ok(report)
    }
}

impl FromStr for Report {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        lazy_static! {
            static ref REPORT: Regex = Regex::new(
                r"^@@(?P<policy>.*)@@(?P<report_type>.*)@@(?P<rule_id>.*)@@(?P<directive_id>.*)@@(?P<serial>.*)@@(?P<component>.*)@@(?P<key_value>.*)@@(?P<timestamp>.*)##(?P<node_id>.*)@#(?s)(?P<msg>.*)$"
            ).expect("Invalid report parsing regex");
        }
        debug!("Parsing report: '{}'", s; "component" => "parser");

        let cap = match REPORT.captures(s) {
            None => {
                warn!("Could not parse report: '{}'", s; "component" => "parser");
                return Err(Error::InvalidReport);
            }
            Some(capture) => capture,
        };

        let report = Report {
            node_id: cap["node_id"].into(),
            start_datetime: DateTime::parse_from_str(&cap["timestamp"], "%Y-%m-%d %H:%M:%S%z")?,
            execution_datetime: DateTime::parse_from_str(&cap["timestamp"], "%Y-%m-%d %H:%M:%S%z")?,
            rule_id: cap["rule_id"].into(),
            directive_id: cap["directive_id"].into(),
            component: cap["component"].into(),
            key_value: cap["key_value"].into(),
            event_type: cap["report_type"].into(),
            msg: cap["msg"].into(),
            policy: cap["policy"].into(),
            serial: cap["serial"].parse::<i32>()?,
        };
        debug!("Parsed report {:#?}", report; "component" => "parser");
        Ok(report)
    }
}

#[derive(Debug, Serialize, Deserialize)]
pub struct RunLog {
    pub info: RunInfo,
    pub reports: Vec<Report>,
}

impl FromStr for RunLog {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        let mut info = None;
        let mut reports = vec![];
        lazy_static! {
            static ref REPORT: Regex =
                Regex::new(r"\n?R: ").expect("Invalid report begin parsing regex");
        }
        for cap in REPORT.split(s).skip(1) {
            let report = match Report::from_str(cap) {
                Ok(report) => report,
                Err(Error::InvalidReport) => continue,
                Err(e) => {
                    return Err(e);
                }
            };

            match info {
                None => {
                    info = Some(RunInfo {
                        node_id: report.node_id.clone(),
                        timestamp: report.start_datetime,
                    });
                }
                Some(ref info) => {
                    if info.node_id != report.node_id {
                        warn!("Wrong node id in report {:#?}, skipping", report; "component" => "parser");
                    }
                    if info.timestamp != report.start_datetime {
                        warn!(
                            "Wrong execution timestamp in report {:#?}, skipping",
                            report; "component" => "parser"
                        );
                    }
                }
            }
            reports.push(report)
        }

        let runlog = match info {
            None => Err(Error::EmptyRunlog)?,
            Some(info) => Ok(RunLog { info, reports }),
        };
        debug!("Parsed runlog {:#?}", runlog; "component" => "parser");
        runlog
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs::read_to_string;

    #[test]
    fn test_parse_report() {
        let report = "@@Common@@result_repaired@@hasPolicyServer-root@@common-root@@0@@CRON Daemon@@None@@2018-08-24 15:55:01+00:00##root@#Cron daemon status was repaired";
        let report = Report::from_str(report).unwrap();
        assert_eq!(
            report,
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
            }
        );

        let report2 = "@@Common@@result_repaired@@hasPolicyServer-root@@common-root@@0@@CRON Daemon@@None@@2018-08-24 15:55:01+00:00##root@#Cron daemon R: status##was @@ repaired\nnext line";
        let report2 = Report::from_str(report2).unwrap();
        assert_eq!(
            report2,
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
                msg: "Cron daemon R: status##was @@ repaired\nnext line".into(),
                policy: "Common".into(),
                node_id: "root".into(),
                serial: 0,
                execution_datetime: DateTime::parse_from_str(
                    "2018-08-24 15:55:01+00:00",
                    "%Y-%m-%d %H:%M:%S%z"
                )
                .unwrap(),
            }
        );
    }

    #[test]
    fn test_display_report() {
        let report = "@@Common@@result_repaired@@hasPolicyServer-root@@common-root@@0@@CRON Daemon@@None@@2018-08-24 15:55:01 +00:00##root@#Cron daemon status was repaired";
        //let report = Report::from_str(report).unwrap();
        assert_eq!(
            report,
            format!(
                "{:}",
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
                }
            )
        );
    }

    #[test]
    fn test_parse_invalid_report() {
        let report = "Not a report";
        assert!(match Report::from_str(report) {
            Err(Error::InvalidReport) => true,
            _ => false,
        });
    }

    #[test]
    fn test_parse_runinfo() {
        let runlog_file = "2018-08-24T15:55:01+00:00@root.log";
        let runinfo = RunInfo::from_str(runlog_file).unwrap();
        assert_eq!(
            runinfo,
            RunInfo {
                timestamp: DateTime::parse_from_str("2018-08-24T15:55:01+00:00", "%+").unwrap(),
                node_id: "root".into(),
            }
        );
    }

    #[test]
    fn test_parse_runlog() {
        let run_log = &read_to_string("tests/runlogs/2018-08-24T15:55:01+00:00@root.log").unwrap();
        let run = RunLog::from_str(run_log).unwrap();
        assert_eq!(run.info.node_id, "root".to_owned());
        assert_eq!(run.reports[0].msg, "Start execution".to_owned());
        assert_eq!(
            run.reports[1].msg,
            "Configuration library initialization\nwas correct".to_owned()
        );
        assert_eq!(
            run.reports[10].msg,
            "Remove file /var/rudder/tmp/rudder_monitoring.csv was correct".to_owned()
        );
    }
}
