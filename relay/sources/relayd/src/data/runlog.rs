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

use crate::{
    data::{
        report::{runlog, RawReport},
        Report, RunInfo,
    },
    error::Error,
};
use serde::{Deserialize, Serialize};
use std::{
    convert::TryFrom,
    fmt::{self, Display},
    fs::read_to_string,
    path::Path,
    str::FromStr,
};
use tracing::{debug, error, warn};

#[derive(Debug, Serialize, Deserialize, PartialEq, Eq)]
pub struct RunLog {
    pub info: RunInfo,
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
                Err(Error::InvalidRunLog)
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

        for report in &reports {
            if info.node_id != report.node_id {
                error!(
                    "Wrong node id in report {:#?}, got {} but should be {}",
                    report, report.node_id, info.node_id
                );
                return Err(Error::InconsistentRunlog);
            }
            if info.timestamp != report.start_datetime {
                error!(
                    "Wrong execution timestamp in report {:#?}, got {} but should be {}",
                    report, report.start_datetime, info.timestamp
                );
                return Err(Error::InconsistentRunlog);
            }
        }
        Ok(Self { info, reports })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs::read_dir;

    #[test]
    fn it_parses_runlog() {
        // For each .json file, compare it with the matching .log
        let mut test_done = 0;
        for entry in read_dir("tests/runlogs/").unwrap() {
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
        assert!(test_done > 0);
    }

    #[test]
    fn it_detect_invalid_node_in_runlog() {
        assert!(
            RunLog::new("2018-08-24T15:55:01+00:00@e745a140-40bc-4b86-b6dc-084488fc906c.log")
                .is_err()
        );
    }
}
