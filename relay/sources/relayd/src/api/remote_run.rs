// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use crate::{
    api::RudderReject,
    configuration::main::RemoteRun as RemoteRunCfg,
    data::node::{Host, NodeId},
    error::RudderError,
    JobConfig,
};
use anyhow::Error;
use bytes::Bytes;
use futures::{stream::select, Stream, StreamExt, TryStreamExt};
use http_body_util::combinators::BoxBody;
use http_body_util::StreamBody;
use hyper::body::Frame;
use regex::Regex;
use std::{collections::HashMap, process::Stdio, str::FromStr, sync::Arc};
use sync_wrapper::SyncStream;
use tokio::{
    io::{AsyncBufReadExt, BufReader},
    process::{Child, Command},
};
use tokio_stream::wrappers::LinesStream;
use tracing::{debug, error, instrument, trace, warn};
use warp::{
    body,
    filters::{method, BoxedFilter},
    path, Filter, Reply,
};

pub fn routes_1(job_config: Arc<JobConfig>) -> BoxedFilter<(impl Reply,)> {
    let base = path!("remote-run" / ..);

    let job_config_node = job_config.clone();
    let node = method::post()
        .map(move || job_config_node.clone())
        .and(base)
        .and(path!("nodes" / String))
        .and(body::form())
        .and_then(move |j, node_id, params| handlers::node(node_id, params, j));

    let job_config_nodes = job_config.clone();
    let nodes = method::post()
        .and(base)
        .and(path!("nodes"))
        .map(move || job_config_nodes.clone())
        .and(body::form())
        .and_then(move |j, params| handlers::nodes(params, j));

    let job_config_all = job_config.clone();
    let all = method::post()
        .and(base)
        .and(path!("all"))
        .map(move || job_config_all.clone())
        .and(body::form())
        .and_then(move |j, params| handlers::all(params, j));

    if job_config.cfg.remote_run.enabled {
        node.or(nodes).or(all).boxed()
    } else {
        base.and_then(|| async move {
            warn!("received remote-run request but remote-run API is disabled");
            Err(warp::reject::not_found())
        })
        .boxed()
    }
}

pub mod handlers {
    use percent_encoding::percent_decode_str;
    use warp::{reject, Rejection, Reply};

    use super::*;
    use crate::JobConfig;

    pub async fn node(
        node_id: String,
        params: HashMap<String, String>,
        job_config: Arc<JobConfig>,
    ) -> Result<impl Reply, Rejection> {
        let node_id = percent_decode_str(&node_id).decode_utf8_lossy().to_string();
        match RemoteRun::new(RemoteRunTarget::Nodes(vec![node_id]), &params) {
            Ok(handle) => handle.run(job_config.clone()).await,
            Err(e) => Err(reject::custom(RudderReject::new(e))),
        }
    }

    pub async fn nodes(
        params: HashMap<String, String>,
        job_config: Arc<JobConfig>,
    ) -> Result<impl Reply, Rejection> {
        match params.get("nodes") {
            Some(nodes) => match RemoteRun::new(
                RemoteRunTarget::Nodes(
                    nodes
                        .split(',')
                        .map(|s| s.to_string())
                        .collect::<Vec<String>>(),
                ),
                &params,
            ) {
                Ok(handle) => handle.run(job_config.clone()).await,
                Err(e) => Err(reject::custom(RudderReject::new(e))),
            },
            None => Err(reject::custom(RudderReject::new("Missing nodes"))),
        }
    }

    pub async fn all(
        params: HashMap<String, String>,
        job_config: Arc<JobConfig>,
    ) -> Result<impl Reply, Rejection> {
        match RemoteRun::new(RemoteRunTarget::All, &params) {
            Ok(handle) => handle.run(job_config.clone()).await,
            Err(e) => Err(reject::custom(RudderReject::new(e))),
        }
    }
}

#[derive(Debug)]
pub struct RemoteRun {
    target: RemoteRunTarget,
    run_parameters: RunParameters,
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

    async fn consume(
        mut stream: impl Stream<Item = Result<Bytes, Error>> + Unpin,
    ) -> Result<(), ()> {
        while let Some(l) = stream.next().await {
            match l {
                Ok(l) => trace!("Read {:#?}", l),
                Err(e) => error!("Stream error: {}", e),
            }
        }
        Ok(())
    }

    #[instrument(name = "remote-run", level = "debug", skip(self, job_config))]
    pub async fn run(
        &self,
        job_config: Arc<JobConfig>,
    ) -> Result<Box<dyn Reply>, warp::reject::Rejection> {
        debug!(
            "Starting remote run (asynchronous: {}, keep_output: {})",
            self.run_parameters.asynchronous, self.run_parameters.keep_output
        );

        // warp expects a (`Sync`) body, so we need to wrap and convert the stream.
        fn body_from_stream(
            stream: Box<dyn Stream<Item = Result<Bytes, Error>> + Unpin + Send>,
        ) -> BoxBody<Bytes, Error> {
            BoxBody::new(StreamBody::new(SyncStream::new(stream).map_ok(Frame::data)))
        }

        match (
            self.run_parameters.asynchronous,
            self.run_parameters.keep_output,
        ) {
            // Async and output -> spawn in background and stream output
            (true, true) => {
                let mut streams = futures::stream::SelectAll::new();
                for (id, host, target) in self.target.next_hops(job_config.clone()).await {
                    let stream = self
                        .forward_call(job_config.clone(), id, host, target.clone())
                        .await;
                    streams.push(stream);
                }

                Ok(Box::new(warp::reply::html(body_from_stream(Box::new(
                    select(
                        self.run_parameters
                            .remote_run(
                                &job_config.cfg.remote_run,
                                self.target.neighbors(job_config.clone()).await,
                                self.run_parameters.asynchronous,
                            )
                            .await,
                        streams,
                    ),
                )))))
            }
            // Async and no output -> spawn in background and return early
            (true, false) => {
                for (id, host, target) in self.target.next_hops(job_config.clone()).await {
                    let stream = self
                        .forward_call(job_config.clone(), id, host, target)
                        .await;
                    tokio::spawn(RemoteRun::consume(stream));
                }
                tokio::spawn(RemoteRun::consume(
                    self.run_parameters
                        .remote_run(
                            &job_config.cfg.remote_run,
                            self.target.neighbors(job_config.clone()).await,
                            self.run_parameters.asynchronous,
                        )
                        .await,
                ));
                Ok(Box::new(warp::reply::html(BoxBody::new(
                    http_body_util::Empty::<Bytes>::new(),
                ))))
            }
            // Sync and no output -> wait until the send and return empty output
            (false, false) => {
                let mut streams = futures::stream::SelectAll::new();
                for (id, host, target) in self.target.next_hops(job_config.clone()).await {
                    let stream = self
                        .forward_call(job_config.clone(), id, host, target.clone())
                        .await;
                    streams.push(stream);
                }

                Ok(Box::new(warp::reply::html(body_from_stream(Box::new(
                    select(
                        self.run_parameters
                            .remote_run(
                                &job_config.cfg.remote_run,
                                self.target.neighbors(job_config.clone()).await,
                                self.run_parameters.asynchronous,
                            )
                            .await
                            .map(|_| Ok(Bytes::from(""))),
                        streams,
                    ),
                )))))
            }
            // Sync and output -> wait until the end and return output
            (false, true) => {
                let mut streams = futures::stream::SelectAll::new();
                for (id, host, target) in self.target.next_hops(job_config.clone()).await {
                    let stream = self
                        .forward_call(job_config.clone(), id, host, target.clone())
                        .await;
                    streams.push(stream);
                }

                Ok(Box::new(warp::reply::html(body_from_stream(Box::new(
                    select(
                        self.run_parameters
                            .remote_run(
                                &job_config.cfg.remote_run,
                                self.target.neighbors(job_config.clone()).await,
                                self.run_parameters.asynchronous,
                            )
                            .await,
                        streams,
                    ),
                )))))
            }
        }
    }

    async fn forward_call(
        &self,
        job_config: Arc<JobConfig>,
        id: NodeId,
        hostname: Host,
        // Target for the sub relay
        target: RemoteRunTarget,
    ) -> Box<dyn Stream<Item = Result<Bytes, Error>> + Unpin + Send> {
        debug!(
            "Forwarding remote-run to {}:{} for {:#?}",
            id, hostname, target
        );

        // We cannot simply serialize it using `.form()` as we
        // need specific formatting
        let mut params = HashMap::new();
        params.insert("keep_output", self.run_parameters.keep_output.to_string());
        params.insert("asynchronous", self.run_parameters.asynchronous.to_string());
        params.insert(
            "classes",
            self.run_parameters
                .conditions
                .iter()
                .map(|c| c.data.as_ref())
                .collect::<Vec<&str>>()
                .join(","),
        );
        if let RemoteRunTarget::Nodes(nodes) = &target {
            params.insert("nodes", nodes.join(","));
        }

        let client = match job_config.downstream_clients.read().await.get(&id) {
            Some(c) => c.clone(),
            None => {
                error!("unknown sub-relay '{}'", id);
                return Box::new(futures::stream::empty());
            }
        };

        let response = client
            .inner()
            .post(format!(
                "https://{}:{}/rudder/relay-api/remote-run/{}",
                hostname,
                job_config.cfg.general.https_port,
                match target {
                    RemoteRunTarget::All => "all",
                    RemoteRunTarget::Nodes(_) => "nodes",
                },
            ))
            .form(&params)
            .send()
            .await
            // Fail if HTTP error
            .and_then(|response| response.error_for_status());

        match response {
            Ok(r) => Box::new(r.bytes_stream().map(|c| c.map_err(|e| e.into()))),
            Err(e) => {
                error!("forward error: {}", e);
                // TODO find a better way to chain errors
                Box::new(futures::stream::empty())
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
    pub async fn neighbors(&self, job_config: Arc<JobConfig>) -> Vec<Host> {
        let nodes = job_config.nodes.read().await;
        let neighbors = match self {
            RemoteRunTarget::All => nodes.my_neighbors(),
            RemoteRunTarget::Nodes(nodeslist) => nodes.my_neighbors_from(nodeslist),
        };
        debug!("Neighbors: {:#?}", neighbors);
        neighbors
    }

    pub async fn next_hops(
        &self,
        job_config: Arc<JobConfig>,
    ) -> Vec<(NodeId, Host, RemoteRunTarget)> {
        let nodes = job_config.nodes.read().await;
        let next_hops: Vec<(NodeId, Host, RemoteRunTarget)> = match self {
            RemoteRunTarget::All => nodes
                .my_sub_relays()
                .into_iter()
                .map(|(id, host)| (id, host, RemoteRunTarget::All))
                .collect(),
            RemoteRunTarget::Nodes(nodeslist) => nodes
                .my_sub_relays_from(nodeslist)
                .into_iter()
                .map(|(id, host, nodes)| (id, host, RemoteRunTarget::Nodes(nodes)))
                .collect(),
        };
        debug!("Next-hops: {:#?}", next_hops);
        next_hops
    }
}

#[derive(Debug, PartialEq)]
struct Condition {
    data: String,
}

impl FromStr for Condition {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        let condition_regex = r"^[a-zA-Z0-9][a-zA-Z0-9_]*$";
        let re = Regex::new(condition_regex).unwrap();
        let max_length = 1024;
        if s.len() > max_length {
            return Err(RudderError::MaxLengthCondition {
                condition: s.to_string(),
                max_length,
            }
            .into());
        }
        if !re.is_match(s) {
            Err(RudderError::InvalidCondition {
                condition: s.to_string(),
                condition_regex,
            }
            .into())
        } else {
            Ok(Condition {
                data: s.to_string(),
            })
        }
    }
}

#[derive(Debug, PartialEq)]
struct RunParameters {
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
            Some(conditions) if !conditions.is_empty() => {
                let split_conditions: Result<Vec<_>, _> =
                    conditions.split(',').map(Condition::from_str).collect();
                split_conditions?
            }
            _ => vec![],
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

    pub fn command(&self, cfg: &RemoteRunCfg, nodes: Vec<String>) -> Command {
        assert!(!nodes.is_empty());

        let mut cmd = if cfg.use_sudo {
            let mut tmp = Command::new("sudo");
            tmp.arg(&cfg.command);
            tmp
        } else {
            Command::new(&cfg.command)
        };
        cmd.arg("remote");
        cmd.arg("run");
        if !&self.conditions.is_empty() {
            cmd.arg("-D");
            cmd.arg(
                self.conditions
                    .iter()
                    .map(|c| c.data.as_str())
                    .collect::<Vec<&str>>()
                    .join(","),
            );
        }
        cmd.arg("--");
        cmd.arg(nodes.join(","));
        debug!("Remote run command: '{:#?}'", cmd);
        cmd
    }

    async fn remote_run(
        &self,
        cfg: &RemoteRunCfg,
        nodes: Vec<String>,
        asynchronous: bool,
    ) -> Box<dyn Stream<Item = Result<Bytes, Error>> + Unpin + Send> {
        trace!("Starting local remote run on {:#?} with {:#?}", nodes, cfg);

        if nodes.is_empty() {
            debug!("No nodes to trigger locally, skipping");
            return Box::new(futures::stream::empty());
        }

        let mut cmd = self.command(cfg, nodes);
        cmd.stdout(Stdio::piped());

        match (asynchronous, cmd.spawn()) {
            (false, Ok(c)) =>
            // send output at once
            {
                Box::new(futures::stream::once(futures::future::ready(
                    c.wait_with_output()
                        .await
                        .map(|o| o.stdout)
                        .map(Bytes::from)
                        .map_err(|e| e.into()),
                )))
            }

            (true, Ok(mut c)) => {
                // stream lines
                let lines = RunParameters::lines_stream(&mut c);
                // FIXME check if it actually runs
                //tokio::spawn(c);
                Box::new(lines.await)
            }
            (_, Err(e)) => {
                error!("Remote run error while running '{:#?}': {}", cmd, e);
                Box::new(futures::stream::once(futures::future::ready(Err(e.into()))))
            }
        }
    }

    /// Stream command output as a stream of lines
    async fn lines_stream(child: &mut Child) -> Box<impl Stream<Item = Result<Bytes, Error>>> {
        let stdout = child
            .stdout
            .take()
            .expect("child did not have a handle to stdout");

        Box::new(
            LinesStream::new(BufReader::new(stdout).lines())
                .map_err(Error::from)
                .inspect(|line| trace!("output: {:?}", line))
                .map(|r| {
                    r.map(|mut l| {
                        l.push('\n');
                        Bytes::from(l)
                    })
                }),
        )
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
    fn it_defines_parameters() {
        assert_eq!(
            RunParameters::new(None, None, Some(&"".to_string())).unwrap(),
            RunParameters {
                asynchronous: false,
                keep_output: false,
                conditions: vec![],
            }
        );
        assert_eq!(
            RunParameters::new(
                Some(&"true".to_string()),
                Some(&"true".to_string()),
                Some(&"test".to_string())
            )
            .unwrap(),
            RunParameters {
                asynchronous: true,
                keep_output: true,
                conditions: vec![Condition::from_str("test").unwrap()],
            }
        );
    }

    #[test]
    fn it_handles_too_long_conditions() {
        assert!(Condition::from_str("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").is_err());
    }
}
