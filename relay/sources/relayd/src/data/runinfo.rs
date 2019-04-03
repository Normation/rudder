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

use crate::{configuration::LogComponent, data::node, error::Error};
use chrono::prelude::*;
use nom::{types::CompleteStr, *};
use serde::{Deserialize, Serialize};
use slog::slog_debug;
use slog_scope::debug;
use std::{
    fmt::{self, Display},
    str::FromStr,
};

#[derive(Debug, Serialize, Deserialize, PartialEq, Eq)]
pub struct RunInfo {
    pub node_id: node::Id,
    pub timestamp: DateTime<FixedOffset>,
}

impl Display for RunInfo {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(f, "{:}@{:}", self.timestamp, self.node_id,)
    }
}

fn parse_iso_date(input: CompleteStr) -> Result<DateTime<FixedOffset>, chrono::format::ParseError> {
    DateTime::parse_from_str(input.as_ref(), "%+")
}

named!(parse_runinfo<CompleteStr, RunInfo>,
    do_parse!(
        timestamp: map_res!(take_until_and_consume_s!("@"), parse_iso_date) >>
        node_id: take_until_and_consume_s!(".") >>
        tag_s!("log") >>
        opt!(tag_s!(".gz")) >>
        (
            RunInfo {
                // FIXME same timestamp format as in the reports?
                timestamp,
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
                debug!("Parsed run info {:#?}", raw_runinfo.1; "component" => LogComponent::Parser);
                Ok(raw_runinfo.1)
            }
            Err(_) => Err(Error::InvalidRunInfo),
        }
    }
}
