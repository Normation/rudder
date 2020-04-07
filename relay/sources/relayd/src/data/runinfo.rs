// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

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
    str::{self, FromStr},
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
    let (i, timestamp) = map_res(take_until("@"), |d: &str| {
        // On Windows, filenames can't contain : so we replace them by underscores
        DateTime::parse_from_str(&d.replace("_", ":"), "%+")
    })(i)?;
    let (i, _) = tag("@")(i)?;
    let (i, node_id) = take_until(".")(i)?;
    let (i, _) = tag(".log")(i)?;
    let (i, _) = opt(tag(".gz"))(i)?;

    if node_id.is_empty() {
        Err(nom::Err::Error(("", nom::error::ErrorKind::Many1)))
    } else {
        Ok((
            i,
            RunInfo {
                timestamp,
                node_id: node_id.to_string(),
            },
        ))
    }
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
    use proptest::{prop_assert, prop_assert_eq, proptest};

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
            RunInfo::from_str("2018-08-24T15_55_01+00_00@e745a140-40bc-4b86-b6dc-084488fc906b.log")
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
        .is_err());
        assert!(RunInfo::from_str("2018-08-24T15:55:01+00:00@.log.gz").is_err());
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

    proptest! {
        #[test]
        fn it_parses_runinfo(y in 2000u32..2100,
                             m in 1u32..13, d in 1u32..29,
                             h in 0u32..24, min in 0u32..60,
                             s in 0u32..60, ref pm in "[+-]",
                             tmh in 0u32..13, tmm in 0u32..60,
                             ref id in r"[a-zA-Z0-1-]+") {
            let reference = RunInfo {
                timestamp: DateTime::parse_from_str(&format!("{:04}-{:02}-{:02}T{:02}:{:02}:{:02}{}{:02}:{:02}", y, m, d, h, min, s, pm, tmh, tmm), "%+").expect("invalid date"),
                node_id: id.clone(),
            };

            let runinfo = RunInfo::from_str(
                &format!("{:04}-{:02}-{:02}T{:02}:{:02}:{:02}{}{:02}:{:02}@{}.log", y, m, d, h, min, s, pm, tmh, tmm, id)).unwrap();

            prop_assert_eq!(runinfo, reference);
        }

        #[test]
        fn it_parses_random_runinfo(ref runinfo in r"\w+") {
            let runinfo = RunInfo::from_str(runinfo);
            prop_assert!(runinfo.is_err() || runinfo.is_ok());
        }
    }
}
