// Copyright 2019 Normation SAS
//
// This file is part of Rudder.
//
// Rudder is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// In accordance with the terms of section 7 (7. Additional Terms.) of
// the GNU General Public License version 3, the copyright holders add
// the following Additional permissions:
// Notwithstanding to the terms of section 5 (5. Conveying Modified Source
// Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
// Public License version 3, when you create a Related Module, this
// Related Module is not considered as a part of the work and may be
// distributed under the license agreement of your choice.
// A "Related Module" means a set of sources files including their
// documentation that, without modification of the Source Code, enables
// supplementary functions or services in addition to those offered by
// the Software.
//
// Rudder is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

use crate::{data::nodes::NodeId, error::Error, output::database::schema::ruddersysevents};
use chrono::prelude::*;
use nom::types::CompleteStr;
use nom::*;
use serde::{Deserialize, Serialize};
use slog::{slog_debug, slog_warn};
use slog_scope::{debug, warn};
use std::{
    fmt::{self, Display},
    str::FromStr,
};

/*
"^@@(?P<policy>.*)@@(?P<event_type>.*)@@(?P<rule_id>.*)@@(?P<directive_id>.*)@@(?P<serial>.*)@@
(?P<component>.*)@@(?P<key_value>.*)@@(?P<start_datetime>.*)##(?P<node_id>.*)@#(?s)(?P<msg>.*)$"
*/

// A detail log entry
#[derive(Debug, PartialEq, Eq)]
struct LogEntry {
    event_type: AgentLogLevel,
    msg: String,
}

type AgentLogLevel = &'static str;

named!(
    agent_log_level<CompleteStr, AgentLogLevel>,
    alt!(
        // CFEngine logs
        tag_s!("CRITICAL:")   => { |_| "log_warn" }  |
        tag_s!("   error:")   => { |_| "log_warn" }  |
        tag_s!(" warning:")   => { |_| "log_warn" }  |
        tag_s!("  notice:")   => { |_| "log_info" }  |
        tag_s!("    info:")   => { |_| "log_info" }  |
        tag_s!(" verbose:")   => { |_| "log_debug" } |
        tag_s!("   debug:")   => { |_| "log_debug" } |
        // ncf logs
        tag_s!("R: [FATAL]")  => { |_| "log_warn" }  |
        tag_s!("R: [ERROR]")  => { |_| "log_warn" }  |
        tag_s!("R: [INFO]")   => { |_| "log_info" }  |
        tag_s!("R: [DEBUG]")  => { |_| "log_debug" } |
        // ncf non-standard log
        tag_s!("R: WARNING")  => { |_| "log_warn" }  |
        // CFEngine stdlib log
        tag_s!("R: DEBUG")    => { |_| "log_debug" } |
        // Untagged non-Rudder reports report, assume info
        non_rudder_report_begin
    )
);

named!(non_rudder_report_begin<CompleteStr, AgentLogLevel>,
    do_parse!(
    tag_s!("R: ") >>
    not!(tag_s!("@@")) >>
    ("log_info")
    )
);

named!(rudder_report_begin<CompleteStr, &str>,
    do_parse!(
    tag_s!("R: @@") >>
    ("")
    )
);

named!(simpleline<CompleteStr, String>, do_parse!(
    not!(alt!(rudder_report_begin | agent_log_level)) >>
    res: take_until_and_consume_s!("\n") >>
    (res.to_string())
));

named!(multilines<CompleteStr, String>,
do_parse!(
    // at least one
    res: many1!(simpleline) >>
    // TODO perf: avoid reallocating everything twice and use the source slice
    (res.join("\n"))
));

named!(
    log_entry<CompleteStr, LogEntry>,
    do_parse!(
        level: agent_log_level
            >> opt!(space)
            >> msg: multilines
            >> (LogEntry {
                event_type: level,
                msg,
            })
    )
);

named!(log_entries<CompleteStr, Vec<LogEntry>>, many0!(log_entry));

named!(parse_runlog<CompleteStr, Vec<RawReport>>,
    many1!(
        report
    )
);

named!(report<CompleteStr, RawReport>, do_parse!(
    // TODO NOT CORRECT
    // pas de line break dans une ligne
    // gérer les reports interrompus
    logs: log_entries >>
    rudder_report_begin >>
    policy: take_until_and_consume_s!("@@") >>
    event_type: take_until_and_consume_s!("@@") >>
    rule_id: take_until_and_consume_s!("@@") >>
    directive_id: take_until_and_consume_s!("@@") >>
    serial: take_until_and_consume_s!("@@") >>
    component: take_until_and_consume_s!("@@") >>
    key_value: take_until_and_consume_s!("@@") >>
    start_datetime: take_until_and_consume_s!("##") >>
    node_id: take_until_and_consume_s!("@#") >>
    msg: multilines >>
        (RawReport {
            report: Report {
           // FIXME execution date should be generated at execution
           // We could skip parsing it but it would prevent consistency check that cannot
           // be done once inserted.
            execution_datetime: DateTime::parse_from_str(&start_datetime.to_string(), "%Y-%m-%d %H:%M:%S%z").unwrap(),
            node_id: node_id.to_string(),
            rule_id: rule_id.to_string(),
            directive_id: directive_id.to_string(),
            serial: serial.parse::<i32>().unwrap(),
            component: component.to_string(),
            key_value: key_value.to_string(),
            start_datetime: DateTime::parse_from_str(&start_datetime.to_string(), "%Y-%m-%d %H:%M:%S%z").unwrap(),
            event_type: event_type.to_string(),
            msg: msg.to_string(),
            policy: policy.to_string(),
        },
        logs
        })
));

#[derive(Debug, PartialEq, Eq)]
pub struct RawReport {
    report: Report,
    logs: Vec<LogEntry>,
}

impl RawReport {
    fn into_reports(self) -> Vec<Report> {
        let mut res = vec![];
        for log in self.logs {
            res.push(Report {
                event_type: log.event_type.to_string(),
                msg: log.msg,
                ..self.report.clone()
            })
        }
        res.push(self.report);
        res
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Insertable)]
#[table_name = "ruddersysevents"]
pub struct Report {
    #[column_name = "executiondate"]
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
    #[column_name = "executiontimestamp"]
    pub execution_datetime: DateTime<FixedOffset>,
    pub serial: i32,
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
            self.msg,
        )
    }
}

impl RunLog {
    fn from_reports(raw_reports: Vec<RawReport>) -> Result<Self, Error> {
        let reports: Vec<Report> = raw_reports
            .into_iter()
            .flat_map(|x| x.into_reports())
            .collect();

        let info = match reports.first() {
            None => return Err(Error::EmptyRunlog),
            Some(report) => RunInfo {
                node_id: report.node_id.clone(),
                timestamp: report.start_datetime,
            },
        };

        for report in &reports {
            if info.node_id != report.node_id {
                warn!("Wrong node id in report {:#?}", report; "component" => "parser");
            }
            if info.timestamp != report.start_datetime {
                warn!(
                    "Wrong execution timestamp in report {:#?}",
                    report; "component" => "parser"
                );
            }
        }
        Ok(RunLog { info, reports })
    }
}

#[derive(Debug, Serialize, Deserialize, PartialEq, Eq)]
pub struct RunInfo {
    pub node_id: NodeId,
    pub timestamp: DateTime<FixedOffset>,
}

named!(parse_runinfo<CompleteStr, RunInfo>,
    do_parse!(
        timestamp: take_until_and_consume_s!("@") >>
        node_id: take_until_and_consume_s!(".") >>
        tag_s!("log") >>
        (
            RunInfo {
                // FIXME same timestamp format as in the reports?
                timestamp: DateTime::parse_from_str(&timestamp.to_string(), "%+").unwrap(),
                node_id: node_id.to_string(),
            }
        )
    )
);

impl FromStr for RunInfo {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        match parse_runinfo(CompleteStr::from(s)) {
            Ok(raw_runinfo) => {
                debug!("Parsed run info {:#?}", raw_runinfo.1; "component" => "parser");
                Ok(raw_runinfo.1)
            }
            Err(_) => Err(Error::InvalidRunInfo),
        }
    }
}

#[derive(Debug, Serialize, Deserialize)]
pub struct RunLog {
    pub info: RunInfo,
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

impl FromStr for RunLog {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        match parse_runlog(CompleteStr::from(s)) {
            Ok(raw_runlog) => {
                debug!("Parsed runlog {:#?}", raw_runlog.1; "component" => "parser");
                Ok(Self::from_reports(raw_runlog.1)?)
            }
            Err(_) => Err(Error::InvalidRunInfo),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs::read_to_string;

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
    fn test_parse_log_level() {
        assert_eq!(
            agent_log_level(CompleteStr::from("CRITICAL: toto"))
                .unwrap()
                .1,
            "log_warn"
        )
    }

    #[test]
    fn test_parse_multiline() {
        assert_eq!(
            simpleline(CompleteStr::from("The thing\n")).unwrap().1,
            "The thing".to_string()
        );
        assert_eq!(
            simpleline(CompleteStr::from("The thing\nR: report"))
                .unwrap()
                .1,
            "The thing".to_string()
        );
        assert!(simpleline(CompleteStr::from("R: The thing\nreport")).is_err());
        assert!(simpleline(CompleteStr::from("CRITICAL: plop\nreport")).is_err());
    }

    #[test]
    fn test_parse_log_entry() {
        assert_eq!(
            log_entry(CompleteStr::from("CRITICAL: toto\n")).unwrap().1,
            LogEntry {
                event_type: "log_warn",
                msg: "toto".to_string(),
            }
        )
    }

    #[test]
    fn test_parse_log_entries() {
        assert_eq!(
            log_entries(CompleteStr::from("CRITICAL: toto\nsuite\nCRITICAL: tutu\n"))
                .unwrap()
                .1,
            vec![
                LogEntry {
                    event_type: "log_warn",
                    msg: "toto\nsuite".to_string(),
                },
                LogEntry {
                    event_type: "log_warn",
                    msg: "tutu".to_string()
                }
            ]
        )
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
            run.reports[11].msg,
            "Remove file /var/rudder/tmp/rudder_monitoring.csv was correct".to_owned()
        );
    }
}
