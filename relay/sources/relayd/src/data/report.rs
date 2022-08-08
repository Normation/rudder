// SPDX-License-Identifier: GPL-3.0-or-later WITH GPL-3.0-linking-source-exception
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

// Prevent warning with diesel::Insertable
#![allow(clippy::extra_unused_lifetimes)]

use crate::{data::node::NodeId, output::database::schema::ruddersysevents};
use chrono::prelude::*;
use nom::{
    branch::alt,
    bytes::complete::{tag, take_till, take_until},
    combinator::{map, map_res, not, opt},
    multi::{many0, many1},
    IResult,
};
use serde::{Deserialize, Serialize};
use std::fmt::{self, Display};

type AgentLogLevel = &'static str;

// A detail log entry
#[derive(Debug, PartialEq, Eq)]
struct LogEntry {
    event_type: AgentLogLevel,
    msg: String,
    datetime: DateTime<FixedOffset>,
}

/// Tries to catch as many log levels as possible
/// Definitely a best-effort approach
fn agent_log_level(i: &str) -> IResult<&str, AgentLogLevel> {
    let (i, res) = alt((
        // CFEngine logs
        map(tag("CRITICAL"), |_| "log_warn"),
        map(tag("   error"), |_| "log_warn"),
        map(tag(" warning"), |_| "log_warn"),
        map(tag("  notice"), |_| "log_info"),
        map(tag("    info"), |_| "log_info"),
        map(tag(" verbose"), |_| "log_debug"),
        map(tag("   debug"), |_| "log_debug"),
        // At log level >= info, CFEngine adds the program name
        // https://github.com/cfengine/core/blob/f57d0359757c6adb7ec2416f2072546b8db1181b/libutils/logging.c#L223
        // For us it should always be "rudder" as it is part of our policies
        map(tag("rudder CRITICAL"), |_| "log_warn"),
        map(tag("rudder    error"), |_| "log_warn"),
        map(tag("rudder  warning"), |_| "log_warn"),
        map(tag("rudder   notice"), |_| "log_info"),
        map(tag("rudder     info"), |_| "log_info"),
        // ncf logs
        map(tag("R: [FATAL]"), |_| "log_warn"),
        map(tag("R: [ERROR]"), |_| "log_warn"),
        map(tag("R: [INFO]"), |_| "log_info"),
        map(tag("R: [DEBUG]"), |_| "log_debug"),
        // ncf non-standard log
        map(tag("R: WARNING"), |_| "log_warn"),
        // CFEngine stdlib log
        map(tag("R: DEBUG"), |_| "log_warn"),
        // Untagged non-Rudder reports report, assume info
        non_rudder_report_begin,
    ))(i)?;
    // Allow colon after any log level as wild reports are not very consistent
    let (i, _) = opt(tag(":"))(i)?;
    // Remove spaces after detected log level if any
    let (i, _) = many0(tag(" "))(i)?;
    Ok((i, res))
}

fn non_rudder_report_begin(i: &str) -> IResult<&str, AgentLogLevel> {
    // A space is already hardcoded after each agent_log_level
    let (i, _) = tag("R:")(i)?;
    let (i, _) = not(tag(" @@"))(i)?;
    Ok((i, "log_info"))
}

fn rudder_report_begin(i: &str) -> IResult<&str, &str> {
    let (i, _) = tag("R: @@")(i)?;
    // replace "" by ()?
    Ok((i, ""))
}

// TODO make a cheap version that does not parse the date?
fn line_timestamp(i: &str) -> IResult<&str, DateTime<FixedOffset>> {
    let (i, datetime) = map_res(take_until(" "), |d| DateTime::parse_from_str(d, "%+"))(i)?;
    let (i, _) = tag(" ")(i)?;
    Ok((i, datetime))
}

fn simpleline(i: &str) -> IResult<&str, &str> {
    let (i, _) = opt(line_timestamp)(i)?;
    let (i, _) = not(alt((agent_log_level, map(tag("R: @@"), |_| ""))))(i)?;
    // Compatible with all possible line endings: \n, \r or \r\n
    // * MIME line endings are \r\n
    // * Log lines can contain \r
    // * compatible with simple \n for easier testing
    let (i, res) = take_till(|c| c == '\n' || c == '\r')(i)?;
    let (i, _) = alt((tag("\r\n"), tag("\r"), tag("\n")))(i)?;
    Ok((i, res))
}

fn multilines(i: &str) -> IResult<&str, Vec<&str>> {
    let (i, res) = many1(simpleline)(i)?;
    Ok((i, res))
}

fn log_entry(i: &str) -> IResult<&str, LogEntry> {
    let (i, datetime) = line_timestamp(i)?;
    let (i, event_type) = agent_log_level(i)?;
    let (i, msg) = multilines(i)?;
    Ok((
        i,
        LogEntry {
            event_type,
            msg: msg.join("\n"),
            datetime,
        },
    ))
}

fn log_entries(i: &str) -> IResult<&str, Vec<LogEntry>> {
    many0(log_entry)(i)
}

pub fn report(i: &str) -> IResult<&str, ParsedReport> {
    let (i, logs) = log_entries(i)?;
    let (i, execution_datetime) =
        map_res(take_until(" "), |d| DateTime::parse_from_str(d, "%+"))(i)?;
    let (i, _) = tag(" ")(i)?;
    let (i, _) = rudder_report_begin(i)?;
    let (i, policy) = take_until("@@")(i)?;
    let (i, _) = tag("@@")(i)?;
    let (i, event_type) = take_until("@@")(i)?;
    let (i, _) = tag("@@")(i)?;
    let (i, rule_id) = take_until("@@")(i)?;
    let (i, _) = tag("@@")(i)?;
    let (i, directive_id) = take_until("@@")(i)?;
    let (i, _) = tag("@@")(i)?;
    let (i, report_id) = take_until("@@")(i)?;
    let (i, _) = tag("@@")(i)?;
    let (i, component) = take_until("@@")(i)?;
    let (i, _) = tag("@@")(i)?;
    let (i, key_value) = take_until("@@")(i)?;
    let (i, _) = tag("@@")(i)?;
    let (i, start_datetime) = map_res(take_until("##"), |d| {
        DateTime::parse_from_str(d, "%Y-%m-%d %H:%M:%S%z")
    })(i)?;
    let (i, _) = tag("##")(i)?;
    let (i, node_id) = take_until("@#")(i)?;
    let (i, _) = tag("@#")(i)?;
    let (i, msg) = multilines(i)?;
    Ok((
        i,
        Ok(RawReport {
            report: Report {
                // We could skip parsing it but it would prevent consistency check that cannot
                // be done once inserted.
                execution_datetime,
                node_id: node_id.to_string(),
                rule_id: rule_id.to_string(),
                directive_id: directive_id.to_string(),
                report_id: report_id.to_string(),
                component: component.to_string(),
                key_value: key_value.to_string(),
                start_datetime,
                event_type: event_type.to_string(),
                msg: msg.join("\n"),
                policy: policy.to_string(),
            },
            logs,
        }),
    ))
}

/// Skip garbage before a report, useful in case there are
/// very broken (not timestamped) lines for some reason.
fn garbage(i: &str) -> IResult<&str, ParsedReport> {
    let (i, _) = not(line_timestamp)(i)?;
    let (i, res) = simpleline(i)?;
    Ok((i, Err(res.to_string())))
}

// Handle errors: eat the broken report and continue
fn until_next(i: &str) -> IResult<&str, ParsedReport> {
    // The line looking like a report
    let (i, first) = take_until("R: @@")(i)?;
    let (i, tag) = tag("R: @@")(i)?;
    // The end of the broken report
    let (i, multi) = multilines(i)?;
    let mut lines = first.to_string();
    lines.push_str(tag);
    for line in multi {
        lines.push_str(line);
    }
    Ok((i, Err(lines)))
}

fn maybe_report(i: &str) -> IResult<&str, ParsedReport> {
    alt((report, garbage, until_next))(i)
}

pub fn runlog(i: &str) -> IResult<&str, Vec<ParsedReport>> {
    many1(maybe_report)(i)
}

pub type ParsedReport = Result<RawReport, String>;

// We could make RawReport insertable to avoid copying context to simple logs
#[derive(Debug, PartialEq, Eq)]
pub struct RawReport {
    report: Report,
    logs: Vec<LogEntry>,
}

impl RawReport {
    pub fn into_reports(self) -> Vec<Report> {
        let mut res = vec![];
        for log in self.logs {
            res.push(Report {
                event_type: log.event_type.to_string(),
                msg: log.msg,
                execution_datetime: log.datetime,
                ..self.report.clone()
            })
        }
        res.push(self.report);
        res
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Queryable)]
pub struct QueryableReport {
    pub id: i64,
    #[column_name = "executiontimestamp"]
    pub start_datetime: DateTime<Utc>,
    #[column_name = "ruleid"]
    pub rule_id: String,
    #[column_name = "directiveid"]
    pub directive_id: String,
    pub component: String,
    #[column_name = "keyvalue"]
    pub key_value: Option<String>,
    #[column_name = "eventtype"]
    pub event_type: Option<String>,
    #[column_name = "msg"]
    pub msg: Option<String>,
    #[column_name = "policy"]
    pub policy: Option<String>,
    #[column_name = "nodeid"]
    pub node_id: NodeId,
    #[column_name = "executiondate"]
    pub execution_datetime: Option<DateTime<Utc>>,
    pub report_id: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Insertable)]
#[table_name = "ruddersysevents"]
pub struct Report {
    #[column_name = "executiontimestamp"]
    pub start_datetime: DateTime<FixedOffset>,
    #[column_name = "ruleid"]
    pub rule_id: String,
    #[column_name = "directiveid"]
    pub directive_id: String,
    pub component: String,
    #[column_name = "keyvalue"]
    pub key_value: String,
    // Not parsed as we do not use it and do not want to prevent future changes
    #[column_name = "eventtype"]
    pub event_type: String,
    #[column_name = "msg"]
    pub msg: String,
    #[column_name = "policy"]
    pub policy: String,
    #[column_name = "nodeid"]
    pub node_id: NodeId,
    #[column_name = "executiondate"]
    pub execution_datetime: DateTime<FixedOffset>,
    #[column_name = "reportid"]
    pub report_id: String,
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
            self.report_id,
            self.component,
            self.key_value,
            self.start_datetime,
            self.node_id,
            self.msg,
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn it_formats_report() {
        let report = "@@Common@@result_repaired@@hasPolicyServer-root@@common-root@@0@@CRON Daemon@@None@@2018-08-24 15:55:01 +00:00##root@#Cron daemon status was repaired";
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
                    report_id: "0".into(),
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
    fn it_parses_log_level() {
        assert_eq!(agent_log_level("CRITICAL: toto").unwrap().1, "log_warn")
    }

    #[test]
    fn it_parses_simpleline() {
        assert_eq!(simpleline("Thething\n").unwrap().1, "Thething".to_string());
        assert_eq!(
            simpleline("Thething\r\n").unwrap().1,
            "Thething".to_string()
        );
        assert_eq!(
            simpleline("The thing\n").unwrap().1,
            "The thing".to_string()
        );
        assert_eq!(
            simpleline("The thing\r").unwrap().1,
            "The thing".to_string()
        );
        assert_eq!(
            simpleline("2019-05-09T13:36:46+00:00 The thing\n")
                .unwrap()
                .1,
            "The thing".to_string()
        );
        assert_eq!(
            simpleline(
                "2019-05-09T13:36:46+00:00 The thing\n2019-05-09T13:36:46+00:00 The other thing\n"
            )
            .unwrap()
            .1,
            "The thing".to_string()
        );
        assert_eq!(
            simpleline("2019-05-09T13:36:46+00:00 The thing\n2019-05-09T13:36:46+00:00 R: report")
                .unwrap()
                .1,
            "The thing".to_string()
        );
        assert!(simpleline("2019-05-09T13:36:46+00:00 R: The thing\nreport").is_err());
        assert!(simpleline("2019-05-09T13:36:46+00:00 CRITICAL: plop\nreport").is_err());
    }

    #[test]
    fn it_parses_multilines() {
        assert_eq!(
            multilines("Thething\n").unwrap().1.join("\n"),
            "Thething".to_string()
        );
        assert_eq!(
            multilines("The thing\n").unwrap().1.join("\n"),
            "The thing".to_string()
        );
        assert_eq!(
            multilines("2019-05-09T13:36:46+00:00 The thing\n")
                .unwrap()
                .1
                .join("\n"),
            "The thing".to_string()
        );
        assert_eq!(
            multilines(
                "2019-05-09T13:36:46+00:00 The thing\n2019-05-09T13:36:46+00:00 The other thing\n"
            )
            .unwrap()
            .1
            .join("\n"),
            "The thing\nThe other thing".to_string()
        );
        assert_eq!(
            multilines("2019-05-09T13:36:46+00:00 The thing\n\n2019-05-09T13:36:46+00:00 The other thing\n")
            .unwrap().1.join("\n"),
            "The thing\n\nThe other thing".to_string()
        );
        assert_eq!(
            multilines("Thething\n2019-05-09T13:36:46+00:00 Theotherthing\n")
                .unwrap()
                .1
                .join("\n"),
            "Thething\nTheotherthing".to_string()
        );
    }

    #[test]
    fn it_parses_log_entry() {
        assert_eq!(
            log_entry("2019-05-09T13:36:46+00:00 CRITICAL: toto\n")
                .unwrap()
                .1,
            LogEntry {
                event_type: "log_warn",
                msg: "toto".to_string(),
                datetime: DateTime::parse_from_str("2019-05-09T13:36:46+00:00", "%+").unwrap(),
            }
        );
        assert_eq!(
            log_entry("2019-05-09T13:36:46+00:00 CRITICAL:toto\n")
                .unwrap()
                .1,
            LogEntry {
                event_type: "log_warn",
                msg: "toto".to_string(),
                datetime: DateTime::parse_from_str("2019-05-09T13:36:46+00:00", "%+").unwrap(),
            }
        );
        assert_eq!(
            log_entry("2019-05-09T13:36:46+00:00 CRITICAL:     toto\n")
                .unwrap()
                .1,
            LogEntry {
                event_type: "log_warn",
                msg: "toto".to_string(),
                datetime: DateTime::parse_from_str("2019-05-09T13:36:46+00:00", "%+").unwrap(),
            }
        );
        assert_eq!(
            log_entry("2019-05-09T13:36:46+00:00 CRITICAL: toto\n2019-05-09T13:36:46+00:00 CRITICAL: toto2\n").unwrap().1,
            LogEntry {
                event_type: "log_warn",
                msg: "toto".to_string(),
                datetime: DateTime::parse_from_str("2019-05-09T13:36:46+00:00", "%+").unwrap(),
            }
        );
        assert_eq!(
            log_entry("2019-05-09T13:36:46+00:00 CRITICAL: toto\n2019-05-09T13:36:46+00:00 truc\n")
                .unwrap()
                .1,
            LogEntry {
                event_type: "log_warn",
                msg: "toto\ntruc".to_string(),
                datetime: DateTime::parse_from_str("2019-05-09T13:36:46+00:00", "%+").unwrap(),
            }
        );
        assert_eq!(
            log_entry("2019-05-09T13:36:46+00:00 CRITICAL: toto\ntruc\n")
                .unwrap()
                .1,
            LogEntry {
                event_type: "log_warn",
                msg: "toto\ntruc".to_string(),
                datetime: DateTime::parse_from_str("2019-05-09T13:36:46+00:00", "%+").unwrap(),
            }
        );
        assert_eq!(
            log_entry("2019-05-09T13:36:46+00:00 rudder     info: Executing\n")
                .unwrap()
                .1,
            LogEntry {
                event_type: "log_info",
                msg: "Executing".to_string(),
                datetime: DateTime::parse_from_str("2019-05-09T13:36:46+00:00", "%+").unwrap(),
            }
        );

        assert_eq!(
            log_entry("2020-03-24T12:30:27+00:00 CRITICAL: test\rlog\n")
                .unwrap()
                .1,
            LogEntry {
                event_type: "log_warn",
                msg: "test\nlog".to_string(),
                datetime: DateTime::parse_from_str("2020-03-24T12:30:27+00:00", "%+").unwrap(),
            }
        );

        assert_eq!(
            log_entry("2020-11-04T18:03:15+00:00 R: [INFO]: Class prefix is too long - fallbacking to old_class_prefix file_from_string_mustache__etc_pki_consul_csr_json for reporting\r\n")
                .unwrap()
                .1,
            LogEntry {
                event_type: "log_info",
                msg: "Class prefix is too long - fallbacking to old_class_prefix file_from_string_mustache__etc_pki_consul_csr_json for reporting".to_string(),
                datetime: DateTime::parse_from_str("2020-11-04T18:03:15+00:00", "%+").unwrap(),
            }
        );
    }

    #[test]
    fn it_parses_log_entries() {
        assert_eq!(
            log_entries("2019-05-09T13:36:46+00:00 CRITICAL: toto\n2018-05-09T13:36:46+00:00 suite\nend\n2017-05-09T13:36:46+00:00 CRITICAL: tutu\n")
                .unwrap()
                .1,
            vec![
                LogEntry {
                    event_type: "log_warn",
                    msg: "toto\nsuite\nend".to_string(),
                    datetime: DateTime::parse_from_str("2019-05-09T13:36:46+00:00", "%+").unwrap(),
                },
                LogEntry {
                    event_type: "log_warn",
                    msg: "tutu".to_string(),
                    datetime: DateTime::parse_from_str("2017-05-09T13:36:46+00:00", "%+").unwrap(),
                }
            ]
        )
    }

    #[test]
    fn it_parses_report() {
        let report = "2018-08-24T15:55:01+00:00 R: @@Common@@result_repaired@@hasPolicyServer-root@@common-root@@0@@CRON Daemon@@None@@2018-08-24 15:55:01 +00:00##root@#Cron daemon status was repaired\r\n";
        assert_eq!(
            maybe_report(report).unwrap().1.unwrap(),
            RawReport {
                report: Report {
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
                    report_id: "0".into(),
                    execution_datetime: DateTime::parse_from_str(
                        "2018-08-24 15:55:01+00:00",
                        "%Y-%m-%d %H:%M:%S%z"
                    )
                    .unwrap(),
                },
                logs: vec![],
            }
        );
        let report = "garbage\n2018-08-24T15:55:01+00:00 R: @@Common@@result_repaired@@hasPolicyServer-root@@common-root@@0@@CRON Daemon@@None@@2018-08-24 15:55:01 +00:00##root@#Cron daemon status was repaired\r\n";
        let (i, e) = maybe_report(report).unwrap();
        assert!(e.is_err());
        assert_eq!(
            maybe_report(i).unwrap().1.unwrap(),
            RawReport {
                report: Report {
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
                    report_id: "0".into(),
                    execution_datetime: DateTime::parse_from_str(
                        "2018-08-24 15:55:01+00:00",
                        "%Y-%m-%d %H:%M:%S%z"
                    )
                    .unwrap(),
                },
                logs: vec![],
            }
        );
        let report = "2018-08-24T15:55:01+00:00 R: @@Common@@broken\n";
        assert_eq!(
            maybe_report(report).unwrap().1,
            Err("2018-08-24T15:55:01+00:00 R: @@Common@@broken".to_string())
        );
    }

    #[test]
    fn it_parses_until_next() {
        let report = "test\n2018-08-24T15:55:01+00:00 R: @@Common@@broken\n";
        assert_eq!(
            until_next(report).unwrap().1,
            Err("test\n2018-08-24T15:55:01+00:00 R: @@Common@@broken".to_string())
        );
        let report = "2018-08-24T15:55:01+00:00 R: @@Common@@broken\r\n2018-08-24T15:55:01+00:00 R: @@Common@@result_repaired@@hasPolicyServer-root@@common-root@@0@@CRON Daemon@@None@@2018-08-24 15:55:01 +00:00##root@#Cron daemon status was repaired\r\n";
        assert_eq!(
            until_next(report).unwrap().1,
            Err("2018-08-24T15:55:01+00:00 R: @@Common@@broken".to_string())
        );
    }
}
