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

use crate::{data::node::NodeId, error::Error};
use chrono::prelude::*;
use nom::{
    bytes::complete::{tag, take_until},
    combinator::{map_res, opt},
    IResult,
};
use serde::{Deserialize, Serialize};
use std::{
    convert::TryFrom,
    fmt::{self, Display},
    path::Path,
    str::FromStr,
};
use tracing::debug;

#[derive(Debug, Serialize, Deserialize, PartialEq, Eq, Clone)]
pub struct RunInfo {
    pub node_id: NodeId,
    pub timestamp: DateTime<FixedOffset>,
}

impl Display for RunInfo {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(f, "{:}@{:}", self.timestamp, self.node_id,)
    }
}

fn parse_runinfo(i: &str) -> IResult<&str, RunInfo> {
    let (i, timestamp) = map_res(take_until("@"), |d| DateTime::parse_from_str(d, "%+"))(i)?;
    let (i, _) = tag("@")(i)?;
    let (i, node_id) = take_until(".")(i)?;
    let (i, _) = tag(".log")(i)?;
    let (i, _) = opt(tag(".gz"))(i)?;

    Ok((
        i,
        RunInfo {
            timestamp,
            node_id: node_id.to_string(),
        },
    ))
}

impl FromStr for RunInfo {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        match parse_runinfo(s) {
            Ok(raw_runinfo) => {
                debug!("Parsed run info {:#?}", raw_runinfo.1);
                Ok(raw_runinfo.1)
            }
            Err(e) => Err(Error::InvalidRunInfo(format!(
                "invalid runinfo '{}' with {:?}",
                s, e
            ))),
        }
    }
}

impl TryFrom<&Path> for RunInfo {
    type Error = Error;

    fn try_from(path: &Path) -> Result<Self, Self::Error> {
        path.file_name()
            .ok_or_else(|| Error::InvalidFile(path.to_path_buf()))
            .and_then(|file| file.to_str().ok_or(Error::InvalidFileName))
            .and_then(|file| file.parse::<RunInfo>())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn it_parses_root_runinfo() {
        let reference = RunInfo {
            timestamp: DateTime::parse_from_str("2018-08-24T15:55:01+00:00", "%+").unwrap(),
            node_id: "root".into(),
        };
        assert_eq!(
            RunInfo::from_str("2018-08-24T15:55:01+00:00@root.log").unwrap(),
            reference
        );
        assert_eq!(
            RunInfo::from_str("2018-08-24T15:55:01+00:00@root.log.gz").unwrap(),
            reference
        );
    }

    #[test]
    fn it_parses_node_runinfo() {
        let reference = RunInfo {
            timestamp: DateTime::parse_from_str("2018-08-24T15:55:01+00:00", "%+").unwrap(),
            node_id: "e745a140-40bc-4b86-b6dc-084488fc906b".into(),
        };
        assert_eq!(
            RunInfo::from_str("2018-08-24T15:55:01+00:00@e745a140-40bc-4b86-b6dc-084488fc906b.log")
                .unwrap(),
            reference
        );
        assert_eq!(
            RunInfo::from_str(
                "2018-08-24T15:55:01+00:00@e745a140-40bc-4b86-b6dc-084488fc906b.log.gz"
            )
            .unwrap(),
            reference
        );
        assert!(RunInfo::from_str(
            "2018-08-24T15:55:01+00:00@e745a140-40bc-4b86-b6dc-084488fc906b.lg.gz"
        )
        .is_err())
    }

    #[test]
    fn it_parses_runinfo_from_path() {
        let reference = RunInfo {
            timestamp: DateTime::parse_from_str("2018-08-24T15:55:01+00:00", "%+").unwrap(),
            node_id: "root".into(),
        };
        assert_eq!(
            RunInfo::try_from(Path::new("2018-08-24T15:55:01+00:00@root.log")).unwrap(),
            reference
        );
        assert_eq!(
            RunInfo::try_from(Path::new("2018-08-24T15:55:01+00:00@root.log.gz")).unwrap(),
            reference
        );
    }
}
