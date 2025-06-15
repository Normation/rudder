// SPDX-License-Identifier: GPL-3.0-or-later WITH GPL-3.0-linking-source-exception
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use anyhow::{Result, anyhow};
use nom::{
    IResult, Parser,
    branch::alt,
    bytes::complete::{tag, take_till, take_until},
    combinator::{map, not, opt},
    multi::{many0, many1},
};
use serde::Serialize;

// TODO: factorize with relayd
// for now it is a copy of `data/reports.rs`

type AgentLogLevel = &'static str;

// A detail log entry
#[derive(Debug, PartialEq, Eq, Clone, Serialize)]
pub struct Log {
    pub event_type: AgentLogLevel,
    pub msg: String,
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
        // For us, it should always be "rudder" as it is part of our policies
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
        // Untagged non-Rudder report, assume info
        non_rudder_report_begin,
    ))
    .parse(i)?;
    // Allow colon after any log level as wild reports are not very consistent
    let (i, _) = opt(tag(":")).parse(i)?;
    // Remove spaces after detected log level if any
    let (i, _) = many0(tag(" ")).parse(i)?;
    Ok((i, res))
}

fn non_rudder_report_begin(i: &str) -> IResult<&str, AgentLogLevel> {
    // A space is already hardcoded after each agent_log_level
    let (i, _) = tag("R:")(i)?;
    let (i, _) = not(tag(" @@")).parse(i)?;
    Ok((i, "log_info"))
}

fn rudder_report_begin(i: &str) -> IResult<&str, &str> {
    let (i, _) = tag("R: @@")(i)?;
    // replace "" by ()?
    Ok((i, ""))
}

fn simpleline(i: &str) -> IResult<&str, &str> {
    let (i, _) = not(alt((agent_log_level, map(tag("R: @@"), |_| "")))).parse(i)?;
    // Compatible with all possible line endings: \n, \r or \r\n
    // * MIME line endings are \r\n
    // * Log lines can contain \r
    // * compatible with simple \n for easier testing
    let (i, res) = take_till(|c| c == '\n' || c == '\r').parse(i)?;
    let (i, _) = alt((tag("\r\n"), tag("\r"), tag("\n"))).parse(i)?;
    Ok((i, res))
}

/// take_until("@@") without line ending
fn end_metadata(i: &str) -> IResult<&str, &str> {
    let (i, res) = take_until("@@")(i)?;
    // If the result contains line breaks let's fail
    // as it is a multi line metadata
    if res.contains('\n') || res.contains('\r') {
        Err(nom::Err::Error(nom::error::Error::new(
            i,
            nom::error::ErrorKind::Tag,
        )))
    } else {
        Ok((i, res))
    }
}

/// take_until("@@") but handles multiline (date prefix and CR/LF ending)
fn simpleline_until_metadata(i: &str) -> IResult<&str, &str> {
    let (i, _) = not(tag("@@")).parse(i)?;
    // Try to parse as single line metadata.
    // If it fails, parse as a simple line
    let (i, res) = alt((end_metadata, simpleline)).parse(i)?;
    Ok((i, res))
}

fn multilines(i: &str) -> IResult<&str, Vec<&str>> {
    let (i, res) = many1(simpleline).parse(i)?;
    Ok((i, res))
}

/// take_until separator but handles multiline (date prefix and CRLF ending)
fn multilines_metadata(i: &str) -> IResult<&str, Vec<&str>> {
    let (i, res) = many0(simpleline_until_metadata).parse(i)?;
    Ok((i, res))
}

fn log_entry(i: &str) -> IResult<&str, Log> {
    let (i, event_type) = agent_log_level(i)?;
    let (i, msg) = multilines(i)?;
    Ok((
        i,
        Log {
            event_type,
            msg: msg.join("\n"),
        },
    ))
}

fn log_entries(i: &str) -> IResult<&str, Vec<Log>> {
    many0(log_entry).parse(i)
}

pub fn report(i: &str) -> IResult<&str, ParsedReport> {
    let (i, logs) = log_entries(i)?;
    let (i, _) = rudder_report_begin(i)?;
    let (i, _policy) = take_until("@@")(i)?;
    let (i, _) = tag("@@")(i)?;
    let (i, event_type) = take_until("@@")(i)?;
    let (i, _) = tag("@@")(i)?;
    let (i, _rule_id) = take_until("@@")(i)?;
    let (i, _) = tag("@@")(i)?;
    let (i, _directive_id) = take_until("@@")(i)?;
    let (i, _) = tag("@@")(i)?;
    let (i, report_id) = take_until("@@")(i)?;
    let (i, _) = tag("@@")(i)?;
    let (i, component) = take_until("@@")(i)?;
    let (i, _) = tag("@@")(i)?;
    let (i, key_value) = multilines_metadata(i)?;
    let (i, _) = tag("@@")(i)?;
    let (i, _start_datetime) = take_until("##")(i)?;
    let (i, _) = tag("##")(i)?;
    let (i, _node_id) = take_until("@#")(i)?;
    let (i, _) = tag("@#")(i)?;
    let (i, msg) = multilines(i)?;

    let key_value = key_value.join("\n");
    let key_value = if key_value.is_empty() {
        "None".to_string()
    } else {
        key_value
    };

    Ok((
        i,
        Ok(Report {
            // When empty, set "0" value as it is what the webapp expects
            report_id: if report_id.is_empty() {
                "0".to_string()
            } else {
                report_id.to_string()
            },
            component: component.to_string(),
            key_value,
            event_type: event_type.to_string(),
            msg: msg.join("\n"),
            logs,
        }),
    ))
}

/// Skip garbage before a report, useful in case there are
/// very broken (not timestamped) lines for some reason.
fn garbage(i: &str) -> IResult<&str, ParsedReport> {
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
    alt((report, garbage, until_next)).parse(i)
}

fn runlog(i: &str) -> IResult<&str, Vec<ParsedReport>> {
    many1(maybe_report).parse(i)
}

type ParsedReport = std::result::Result<Report, String>;

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct Report {
    pub component: String,
    pub key_value: String,
    pub event_type: String,
    pub msg: String,
    pub report_id: String,
    pub logs: Vec<Log>,
}

impl Report {
    pub fn parse(i: &str) -> Result<Vec<Report>> {
        let result = runlog(i);
        match result {
            Ok((_, run_log)) => {
                let (reports, failed): (Vec<_>, Vec<_>) =
                    run_log.into_iter().partition(Result::is_ok);
                for invalid_report in failed.into_iter().map(Result::unwrap_err) {
                    eprintln!("Invalid report: {}", invalid_report);
                }
                let reports: Vec<Report> = reports.into_iter().map(Result::unwrap).collect();
                Ok(reports)
            }
            Err(e) => Err(anyhow!("{:?}: could not parse report: {i}", e)),
        }
    }
}

pub type RunLog = Vec<Report>;

#[cfg(test)]
mod tests {
    use pretty_assertions::assert_eq;

    use super::*;

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
            simpleline("The thing\n").unwrap().1,
            "The thing".to_string()
        );
        assert_eq!(
            simpleline("The thing\nThe other thing\n").unwrap().1,
            "The thing".to_string()
        );
        assert_eq!(
            simpleline("The thing\nR: report").unwrap().1,
            "The thing".to_string()
        );
        assert!(simpleline("R: The thing\nreport").is_err());
        assert!(simpleline("CRITICAL: plop\nreport").is_err());
    }

    #[test]
    fn it_parses_simpleline_until_metadata() {
        assert_eq!(
            simpleline_until_metadata("Thething\n").unwrap().1,
            "Thething".to_string()
        );
        assert_eq!(
            simpleline_until_metadata("Thething@@plop\n").unwrap().1,
            "Thething".to_string()
        );
        assert_eq!(
            simpleline_until_metadata("Thething@plop\n").unwrap().1,
            "Thething@plop".to_string()
        );
        assert_eq!(
            simpleline_until_metadata("Thething\n2018-08-24T15:55:01+00:00 plop\n")
                .unwrap()
                .1,
            "Thething".to_string()
        );
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
            multilines("The thing\n").unwrap().1.join("\n"),
            "The thing".to_string()
        );
        assert_eq!(
            multilines("The thing\nThe other thing\n")
                .unwrap()
                .1
                .join("\n"),
            "The thing\nThe other thing".to_string()
        );
        assert_eq!(
            multilines("The thing\n\nThe other thing\n")
                .unwrap()
                .1
                .join("\n"),
            "The thing\n\nThe other thing".to_string()
        );
        assert_eq!(
            multilines("Thething\nTheotherthing\n")
                .unwrap()
                .1
                .join("\n"),
            "Thething\nTheotherthing".to_string()
        );
    }

    #[test]
    fn it_parses_multilines_metadata() {
        assert_eq!(
            multilines_metadata("Thething@@").unwrap().1.join("\n"),
            "Thething".to_string()
        );
        assert_eq!(
            multilines_metadata("line1@@line2").unwrap().1.join("\n"),
            "line1".to_string()
        );
        assert_eq!(
            multilines_metadata("line1@line2@line3@@")
                .unwrap()
                .1
                .join("\n"),
            "line1@line2@line3".to_string()
        );
        assert_eq!(
            multilines_metadata("line1\nline2@@line3")
                .unwrap()
                .1
                .join("\n"),
            "line1\nline2".to_string()
        );
        assert_eq!(
            multilines_metadata("line1\r\nline2@@line3")
                .unwrap()
                .1
                .join("\n"),
            "line1\nline2".to_string()
        );
    }

    #[test]
    fn it_parses_log_entry() {
        assert_eq!(
            log_entry("CRITICAL: toto\n").unwrap().1,
            Log {
                event_type: "log_warn",
                msg: "toto".to_string(),
            }
        );
        assert_eq!(
            log_entry("CRITICAL:toto\n").unwrap().1,
            Log {
                event_type: "log_warn",
                msg: "toto".to_string(),
            }
        );
        assert_eq!(
            log_entry("CRITICAL:     toto\n").unwrap().1,
            Log {
                event_type: "log_warn",
                msg: "toto".to_string(),
            }
        );
        assert_eq!(
            log_entry("CRITICAL: toto\nCRITICAL: toto2\n").unwrap().1,
            Log {
                event_type: "log_warn",
                msg: "toto".to_string(),
            }
        );
        assert_eq!(
            log_entry("CRITICAL: toto\ntruc\n").unwrap().1,
            Log {
                event_type: "log_warn",
                msg: "toto\ntruc".to_string(),
            }
        );
        assert_eq!(
            log_entry("CRITICAL: toto\ntruc\n").unwrap().1,
            Log {
                event_type: "log_warn",
                msg: "toto\ntruc".to_string(),
            }
        );
        assert_eq!(
            log_entry("rudder     info: Executing\n").unwrap().1,
            Log {
                event_type: "log_info",
                msg: "Executing".to_string(),
            }
        );

        assert_eq!(
            log_entry("CRITICAL: test\rlog\n").unwrap().1,
            Log {
                event_type: "log_warn",
                msg: "test\nlog".to_string(),
            }
        );

        assert_eq!(
            log_entry("R: [INFO]: Class prefix is too long - fallbacking to old_class_prefix file_from_string_mustache__etc_pki_consul_csr_json for reporting\r\n")
                .unwrap()
                .1,
            Log {
                event_type: "log_info",
                msg: "Class prefix is too long - fallbacking to old_class_prefix file_from_string_mustache__etc_pki_consul_csr_json for reporting".to_string(),
            }
        );
    }

    #[test]
    fn it_parses_log_entries() {
        assert_eq!(
            log_entries("CRITICAL: toto\nsuite\nend\nCRITICAL: tutu\n")
                .unwrap()
                .1,
            vec![
                Log {
                    event_type: "log_warn",
                    msg: "toto\nsuite\nend".to_string(),
                },
                Log {
                    event_type: "log_warn",
                    msg: "tutu".to_string(),
                }
            ]
        )
    }

    #[test]
    fn it_parses_report() {
        let report = "R: @@Common@@result_repaired@@hasPolicyServer-root@@common-root@@0@@CRON Daemon@@None@@2018-08-24 15:55:01 +00:00##root@#Cron daemon status was repaired\r\n";
        assert_eq!(
            maybe_report(report).unwrap().1.unwrap(),
            Report {
                component: "CRON Daemon".into(),
                key_value: "None".into(),
                event_type: "result_repaired".into(),
                msg: "Cron daemon status was repaired".into(),
                report_id: "0".into(),
                logs: vec![],
            }
        );
        let report = "garbage\nR: @@Common@@result_repaired@@hasPolicyServer-root@@common-root@@0@@CRON Daemon@@@@2018-08-24 15:55:01 +00:00##root@#Cron daemon status was repaired\r\n";
        let (i, e) = maybe_report(report).unwrap();
        assert!(e.is_err());
        assert_eq!(
            maybe_report(i).unwrap().1.unwrap(),
            Report {
                component: "CRON Daemon".into(),
                key_value: "None".into(),
                event_type: "result_repaired".into(),
                msg: "Cron daemon status was repaired".into(),
                report_id: "0".into(),
                logs: vec![],
            },
        );
        let report = "R: @@Common@@broken\n";
        assert_eq!(
            maybe_report(report).unwrap().1,
            Err("R: @@Common@@broken".to_string())
        );
        let report = "garbage\nR: @@Common@@result_repaired@@hasPolicyServer-root@@common-root@@@@CRON Daemon@@multi\r\nline@@2018-08-24 15:55:01 +00:00##root@#Cron daemon status was repaired\r\n";
        let (i, e) = maybe_report(report).unwrap();
        assert!(e.is_err());
        assert_eq!(
            maybe_report(i).unwrap().1.unwrap(),
            Report {
                component: "CRON Daemon".into(),
                key_value: "multi\nline".into(),
                event_type: "result_repaired".into(),
                msg: "Cron daemon status was repaired".into(),
                report_id: "0".into(),
                logs: vec![],
            },
        );
        let report = "R: @@Common@@broken\n";
        assert_eq!(
            maybe_report(report).unwrap().1,
            Err("R: @@Common@@broken".to_string())
        );
    }

    #[test]
    fn it_parses_until_next() {
        let report = "test\nR: @@Common@@broken\n";
        assert_eq!(
            until_next(report).unwrap().1,
            Err("test\nR: @@Common@@broken".to_string())
        );
        let report = "R: @@Common@@broken\r\nR: @@Common@@result_repaired@@hasPolicyServer-root@@common-root@@0@@CRON Daemon@@None@@2018-08-24 15:55:01 +00:00##root@#Cron daemon status was repaired\r\n";
        assert_eq!(
            until_next(report).unwrap().1,
            Err("R: @@Common@@broken".to_string())
        );
    }
}
