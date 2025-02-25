use clap::Parser;
use rudder_relayd::data::{Report, report::runlog};
use serde::{Deserialize, Serialize};
use std::fs::read_to_string;
use std::path::PathBuf;

#[derive(Debug, Parser)]
#[command(name = "rudder-report")]
struct Args {
    #[arg(
        short,
        long,
        value_name = "Report file path",
        help = "Parse a rudder agent raw execution file to Json"
    )]
    path: PathBuf,
}

#[derive(Serialize, Deserialize, Debug)]
struct Output {
    reports: Vec<Report>,
}

pub fn parse_raw_report_file(p: PathBuf) -> Vec<Report> {
    let reports = read_to_string(p.clone()).expect("The given path must exist and be readable");
    let mut parsed_reports: Vec<Report> = runlog(&reports)
        .unwrap_or_else(|_| {
            panic!(
                "The file '{}' could not be parsed as a Rudder report file",
                p.clone().display()
            )
        })
        .1
        .into_iter()
        .filter_map(|r| r.ok())
        .flat_map(|raw_report| raw_report.into_reports())
        .collect();
    parsed_reports.sort_by(|a, b| a.execution_datetime.cmp(&b.execution_datetime));
    parsed_reports
}

fn main() {
    let args = Args::parse();
    let output = Output {
        reports: parse_raw_report_file(args.path),
    };
    println!("{}", serde_json::to_string_pretty(&output).unwrap());
}

#[cfg(test)]
mod tests {
    use std::path::PathBuf;

    use chrono::DateTime;
    use rudder_relayd::data::Report;

    use crate::parse_raw_report_file;

    #[test]
    fn test_parsing_raw_linux_agent() {
        let parsed_reports = parse_raw_report_file(PathBuf::from("./tests/linux-run.log"));
        let expected = Report {
            start_datetime: DateTime::parse_from_str(
                "2017-08-24 15:55:01+00:00",
                "%Y-%m-%d %H:%M:%S%z",
            )
            .expect("Invalid datetime format"),
            rule_id: "rudder".to_string(),
            directive_id: "run".to_string(),
            component: "start".to_string(),
            key_value: "20180824-130007-3ad37587".to_string(),
            event_type: "control".to_string(),
            msg: "Start execution".to_string(),
            policy: "Common".to_string(),
            node_id: "e745a140-40bc-4b86-b6dc-084488fc906b".to_string(),
            execution_datetime: DateTime::parse_from_str(
                "2019-05-11 12:58:04+00:00",
                "%Y-%m-%d %H:%M:%S%z",
            )
            .expect("Invalid datetime format"),
            report_id: "0".to_string(),
        };
        assert_eq!(parsed_reports[0], expected);
    }

    #[test]
    fn test_parsing_raw_windows_agent() {
        let parsed_reports = parse_raw_report_file(PathBuf::from("./tests/windows-run.log"));
        let expected = Report {
            start_datetime: DateTime::parse_from_str(
                "2024-10-28 14:01:42+00:00",
                "%Y-%m-%d %H:%M:%S%z",
            )
            .expect("Invalid datetime format"),
            rule_id: "rudder".to_string(),
            directive_id: "run".to_string(),
            component: "start".to_string(),
            key_value: "20241028-140100-e53458a2".to_string(),
            event_type: "control".to_string(),
            msg: "Start execution".to_string(),
            policy: "Common".to_string(),
            node_id: "61cc003c-6296-4c1c-a2de-e2c22a793ff3".to_string(),
            execution_datetime: DateTime::parse_from_str(
                "2024-10-28 14:01:45+00:00",
                "%Y-%m-%d %H:%M:%S%z",
            )
            .expect("Invalid datetime format"),
            report_id: "0".to_string(),
        };
        assert_eq!(parsed_reports[0], expected);
    }
}
