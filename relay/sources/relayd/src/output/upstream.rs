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

use crate::Error;
use crate::JobConfig;
use futures::Future;
use std::path::PathBuf;
use std::sync::Arc;
use tracing::{debug, span, Level};

pub fn send_report(
    job_config: Arc<JobConfig>,
    path: PathBuf,
) -> Box<dyn Future<Item = (), Error = Error> + Send> {
    let report_span = span!(Level::TRACE, "upstream");
    let _report_enter = report_span.enter();
    Box::new(forward_file(job_config, "report", path))
}

fn forward_file(
    job_config: Arc<JobConfig>,
    endpoint: &str,
    path: PathBuf,
) -> impl Future<Item = (), Error = Error> + '_ {
    tokio::fs::read(path)
        .map_err(|e| e.into())
        .and_then(move |d| {
            job_config
                .client
                .clone()
                .expect("HTTP client should be initialized")
                .put(&format!(
                    "{}/{}/",
                    job_config.cfg.output.upstream.url, endpoint
                ))
                .basic_auth(
                    &job_config.cfg.output.upstream.user,
                    Some(&job_config.cfg.output.upstream.password),
                )
                .body(d)
                .send()
                .map(|r| debug!("Server response: {:#?}", r))
                .map_err(|e| e.into())
        })
}
