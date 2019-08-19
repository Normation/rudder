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
    path::PathBuf,
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
                if options.contains_key("conditions") {
                    options.get("conditions")
                } else {
                    options.get("classes")
                },
            )?,
        })
    }

    pub fn run(
        &self,
        job_config: Arc<JobConfig>,
    ) -> Result<impl warp::reply::Reply, warp::reject::Rejection> {
        if self.run_parameters.keep_output && self.run_parameters.asynchronous {
            return Err(warp::reject::custom(
                "keep_output and asynchronous cannot be true simultaneously",
            ));
        }
        if self.target == RemoteRunTarget::All {
            info!("remote-run triggered on all the nodes");

            if self.run_parameters.keep_output
                && self.target.get_connected_nodes(job_config.clone()).len() == 1
            {
                Ok(warp::reply::html(hyper::Body::wrap_stream(
                    self.run_parameters.execute_agent_output(
                        job_config.clone(),
                        self.target.get_connected_nodes(job_config.clone()),
                    ),
                )))
            } else {
                self.run_parameters.execute_agent_no_output(
                    job_config.clone(),
                    self.target.get_connected_nodes(job_config.clone()),
                );
                Ok(warp::reply::html(hyper::Body::empty()))
            }
        } else {
            info!("Remote run launched on nodes: {:?}", self.target);

            if self.run_parameters.keep_output
                && self.target.get_connected_nodes(job_config.clone()).len() == 1
            {
                Ok(warp::reply::html(hyper::Body::wrap_stream(
                    self.run_parameters.execute_agent_output(
                        job_config.clone(),
                        self.target.get_connected_nodes(job_config.clone()),
                    ),
                )))
            } else {
                self.run_parameters.execute_agent_no_output(
                    job_config.clone(),
                    self.target.get_connected_nodes(job_config.clone()),
                );
                Ok(warp::reply::html(hyper::Body::empty()))
            }
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RemoteRunTarget {
    All,
    Nodes(Vec<String>),
}

impl RemoteRunTarget {
    pub fn get_connected_nodes(&self, job_config: Arc<JobConfig>) -> Vec<String> {
        match self {
            RemoteRunTarget::All => job_config
                .nodes
                .read()
                .expect("Cannot read nodes list")
                .get_neighbors_from_target(RemoteRunTarget::All),
            RemoteRunTarget::Nodes(nodeslist) => job_config
                .nodes
                .read()
                .expect("Cannot read nodes list")
                .get_neighbors_from_target(RemoteRunTarget::Nodes(nodeslist.clone())),
        }
    }
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
        if s.len() > 1024 {
            return Err(Error::InvalidCondition(
                "Wrong condition: A condition cannot be longer than 1024 characters".to_string(),
            ));
        }
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

    pub fn command(&self, is_root: bool, job_config: Arc<JobConfig>) -> (PathBuf, Vec<String>) {
        let program = job_config.cfg.remote_run.command.clone();

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

    pub fn execute_agent_output(
        &self,
        job_config: Arc<JobConfig>,
        nodeslist: Vec<String>,
    ) -> impl Stream<Item = hyper::Chunk, Error = Error> + Send + 'static {
        let (program, args) = self.command(false, job_config.clone());

        let mut cmd = if job_config.cfg.remote_run.use_sudo {
            let mut tmp = Command::new("sudo");
            tmp.arg(&program);
            tmp
        } else {
            Command::new(&program)
        };

        cmd.args(&args);
        cmd.arg(&nodeslist[0]);
        cmd.stdout(Stdio::piped());
        let mut child = cmd.spawn_async().expect("failed to spawn command");
        let lines = lines_stream(&mut child);
        let child_future = child
            .map(|_status| info!("conditions OK"))
            .map_err(|e| panic!("error while running child: {}", e));

        tokio::spawn(child_future);
        lines
    }

    pub fn execute_agent_no_output(
        &self,
        job_config: Arc<JobConfig>,
        nodeslist: Vec<String>,
    ) -> impl Stream<Item = hyper::Chunk, Error = Error> + Send + 'static {
        let (program, args) = self.command(false, job_config.clone());

        for node in nodeslist {
            if job_config.cfg.remote_run.use_sudo {
                let mut cmd = Command::new("sudo");
                cmd.arg(&program);
                cmd.args(&args);
                cmd.arg(node);
                cmd.stdout(Stdio::piped());
                let child = cmd.spawn_async().expect("failed to spawn command");
                let child_future = child
                    .map(|_status| info!("conditions OK"))
                    .map_err(|e| panic!("error while running child: {}", e));

                tokio::spawn(child_future);
            } else {
                let mut cmd = Command::new(&program);
                cmd.args(&args);
                cmd.arg(node);
                cmd.stdout(Stdio::piped());
                let child = cmd.spawn_async().expect("failed to spawn command");
                let child_future = child
                    .map(|_status| info!("conditions OK"))
                    .map_err(|e| panic!("error while running child: {}", e));

                tokio::spawn(child_future);
            }
        }
        futures::stream::empty()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn it_handles_command_injection() {
        assert!(Condition::from_str("cl$$y").is_err());
        assert!(Condition::from_str("cl~#~").is_err());
    }

    #[test]
    fn it_handles_too_long_conditions() {
        assert!(Condition::from_str("Qr6U6s161z8umvzZTMSPtsZpe3s2sAjwUeCD5pbzvwtT9jg8AsqaW1hbgJhDvOQ34J6GdUS0bEJLKz4zfWHO70rYdq70jrKip5gYwdbVyB7APyK3RRAGHGS7EZ8bUNEXUlHp1QsYOQeqPyPKCCJUYhAzWsD8b1lC4gOkmzATyabEBhaoAb5TLELtBra5dS1YzG1TxgHEthd8z7Qf7PHeltK1X628rfwPqVY2FHkgBGvNMAFTYUdnyabV0j7PHal4f31nNRCqZPdUv6iIlHHQo0oUQlwZ7ATUNYt2cznLYu5v8RhBL0uqOxMD9xHAnRxYRo57BDQxkunNyb7oTjruainGIqbXoDPjcKCQRrf3IrVvAQ6mwAgIdEzJkxBaZUkAGeNQFZEh5b3zJSryfgML2kc87ohLMmsIh5OvNnrPUipSnkpGruJV2uCRX1EYNH6skC9QY1oji6D3SYNeH0lZFIe8goO0Sa1geORlB5UpDwrGeWKgo6k7xBORpPdiVFjR1fAsO7po2CPrR2OwBv6IP0VcU4pPY3eIXgSWSecRE4UXDR2dyaSqSyo4E2l4KAIwy7LieKechiA3yROPrkk0MBC6JfUeOXrCvFBDpQ29Q0TE1J8LK0Xt8DexBZdTUI2ni3Gs1Clli4cvXwfyvTGWFpnTsgS7S7zOyYaIGVqI8UmmszQM8Y4IZBt5nmUsMcrsNBvp4ZqseHoaR0WHTp93c6l83dw3EuuQyFvbqmwQAeDNOrSW2YYAL6Ab5ru5XoRfxCB0LitHWeocyUCo6ukE7YnS8ZmqBIWjLizUD7OnaCSWajdalXINhHDmUQgBehAbPOOiFSlLEyUQeBfZEmWvV5CJ4NN2gBgpDGJywm9mKxr8KcN1TPtp4rGpVYWgDK4N3RjUcQiH7rkSN2zd3vb1MkvtvQsMSX45CpmVng6UQf2LPeRIBNBEaiiNeQAvhfTm86EWNkOwnhHr8QHd7yzLQ6kd4D7Q05oNkRrDDNn5zhS6rvJCujTVFqp5eMa2jbiUa").is_err());
    }
}
