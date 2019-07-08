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

use crate::{error::Error, JobConfig};
use futures::{Future, Stream};
use hyper::Chunk;
use regex::Regex;
use std::{
    collections::HashMap,
    io::BufReader,
    process::{Command, Stdio},
    str::FromStr,
    sync::Arc,
};
use tokio_process::{Child, CommandExt};
use tracing::info;

#[derive(Debug)]
pub struct RemoteRun {
    pub target: RemoteRunTarget,
    pub run_parameters: RunParameters,
}

impl RemoteRun {
    pub fn new(target: RemoteRunTarget, options: &HashMap<String, String>) -> Result<Self, Error> {
        Ok(RemoteRun {
            target,
            run_parameters: RunParameters::new(
                options.get("asynchronous"),
                options.get("keep_output"),
                options.get("classes"),
            )?,
        })
    }

    pub fn run(
        &self,
        job_config: Arc<JobConfig>,
    ) -> Result<impl warp::reply::Reply, warp::reject::Rejection> {
        if self.target == RemoteRunTarget::All {
            info!("conditions OK");
            info!("remote-run triggered on all the nodes");

            for node in job_config
                .nodes
                .read()
                .expect("Cannot read nodes list")
                .get_neighbors_from_target(RemoteRunTarget::All)
            {
                info!("command executed :  \n on node {}", node);
            }
            Ok(warp::reply::html(hyper::Body::wrap_stream(
                self.run_parameters.execute_agent(),
            )))
        } else {
            info!("conditions OK");
            info!("Remote run launched on nodes: {:?}", self.target);

            Ok(warp::reply::html(hyper::Body::wrap_stream(
                self.run_parameters.execute_agent(),
            )))
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RemoteRunTarget {
    All,
    Nodes(Vec<String>),
}

#[derive(Debug)]
pub struct Condition {
    data: String,
}

impl FromStr for Condition {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        let condition_regex = r"^[a-zA-Z0-9][a-zA-Z0-9_]*$";
        let re = Regex::new(condition_regex).unwrap();

        if !re.is_match(s) {
            Err(Error::InvalidCondition(format!(
                "Wrong condition: '{}', it should match {}",
                s.to_string(),
                condition_regex
            )))
        } else {
            Ok(Condition {
                data: s.to_string(),
            })
        }
    }
}

fn lines_stream(
    child: &mut Child,
) -> impl Stream<Item = hyper::Chunk, Error = Error> + Send + 'static {
    let stdout = child
        .stdout()
        .take()
        .expect("child did not have a handle to stdout");

    tokio_io::io::lines(BufReader::new(stdout))
        .map_err(Error::from)
        .inspect(|line| println!("Line: {}", line))
        .map(Chunk::from)
}

#[derive(Debug)]
pub struct RunParameters {
    asynchronous: bool,
    keep_output: bool,
    conditions: Vec<Condition>,
}

impl RunParameters {
    pub fn new(
        raw_asynchronous: Option<&String>,
        raw_keep_output: Option<&String>,
        raw_conditions: Option<&String>,
    ) -> Result<Self, Error> {
        let conditions: Vec<_> = match raw_conditions {
            Some(conditions) => {
                let split_conditions: Result<Vec<_>, _> = conditions
                    .split(',')
                    .map(|s| Condition::from_str(s))
                    .collect();
                split_conditions?
            }
            None => vec![],
        };
        let asynchronous = match raw_asynchronous {
            Some(asynchronous) => asynchronous.parse::<bool>()?,
            None => false,
        };
        let keep_output = match raw_keep_output {
            Some(keep_output) => keep_output.parse::<bool>()?,
            None => false,
        };

        Ok(RunParameters {
            asynchronous,
            keep_output,
            conditions,
        })
    }

    pub fn command(&self, is_root: bool, test_mode: bool) -> (String, Vec<String>) {
        let program = if test_mode {
            "echo"
        } else {
            // FIXME make it configurable
            "/opt/rudder/bin/rudder"
        }
        .to_string();

        let mut args = vec![];
        args.push(if is_root { "agent" } else { "remote" }.to_string());
        args.push("run".to_string());
        if !&self.conditions.is_empty() {
            args.push("-D".to_string());
            let conditions_argument: Vec<String> =
                self.conditions.iter().map(|c| c.data.clone()).collect();
            args.push(conditions_argument.join(","));
        }

        (program, args)
    }

    pub fn execute_agent(
        &self,
    ) -> impl Stream<Item = hyper::Chunk, Error = Error> + Send + 'static {
        let (program, args) = self.command(false, true);
        let mut cmd = Command::new(program);
        cmd.args(args);

        cmd.stdout(Stdio::piped());
        let mut child = cmd.spawn_async().expect("failed to spawn command");
        let lines = lines_stream(&mut child);
        let child_future = child
            .map(|status| info!("conditions OK"))
            .map_err(|e| panic!("error while running child: {}", e));

        tokio::spawn(child_future);
        lines
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn it_handles_command_injection() {
        assert!(Condition::from_str("cl&$$y").is_err());
    }
}
