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

use crate::{api::ApiResult, check_configuration, output::database::ping, Error, JobConfig};
use serde::Serialize;
use std::sync::Arc;

#[derive(Serialize, Debug, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub struct State {
    status: ApiResult,
    #[serde(skip_serializing_if = "Option::is_none")]
    details: Option<String>,
}

impl From<Result<(), Error>> for State {
    fn from(result: Result<(), Error>) -> Self {
        match result {
            Ok(()) => State {
                status: ApiResult::Success,
                details: None,
            },
            Err(e) => State {
                status: ApiResult::Error,
                details: Some(e.to_string()),
            },
        }
    }
}

#[derive(Serialize, Debug, PartialEq, Eq)]
pub struct NodeCounts {
    // Total nodes under this relays
    pub sub_nodes: usize,
    // Nodes directly managed by this relay
    pub managed_nodes: usize,
}

#[derive(Serialize, Debug, PartialEq, Eq)]
pub struct Status {
    database: Option<State>,
    configuration: State,
}

impl Status {
    pub fn poll(job_config: Arc<JobConfig>) -> Self {
        Self {
            database: job_config
                .pool
                .clone()
                .map(|p| ping(&p).map_err(|e| e).into()),
            configuration: check_configuration(&job_config.cli_cfg.configuration_dir)
                .map_err(|e| e)
                .into(),
        }
    }
}
