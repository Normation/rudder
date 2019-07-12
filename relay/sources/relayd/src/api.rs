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
    error::Error,
    remote_run::{RemoteRun, RemoteRunTarget},
    shared_files::{metadata_hash_checker, metadata_writer, parse_hash_from_raw, Metadata},
    {stats::Stats, status::Status, JobConfig},
};
use futures::Future;
use std::{
    collections::HashMap,
    net::SocketAddr,
    sync::{Arc, RwLock},
};
use tracing::info;
use warp::Filter;

pub fn api(
    listen: SocketAddr,
    shutdown: impl Future<Item = ()> + Send + 'static,
    job_config: Arc<JobConfig>,
    stats: Arc<RwLock<Stats>>,
) -> impl Future<Item = (), Error = ()> {
    // TODO put these endpoints into relay-api?
    let stats_simple =
        warp::path("stats").map(move || warp::reply::json(&(*stats.clone().read().unwrap())));

    let job_config1 = job_config.clone();
    let status =
        warp::path("status").map(move || warp::reply::json(&Status::poll(job_config1.clone())));

    let relay_api = warp::path("rudder").and(warp::path("relay-api"));
    let remote_run = relay_api.and(warp::path("remote-run"));
    let shared_files = relay_api.and(warp::path("shared-files"));

    let job_config2 = job_config.clone();
    let node_id = warp::path("nodes").and(
        warp::path::param::<String>()
            .and(warp::body::form())
            .and_then(move |node_id, simple_map: HashMap<String, String>| {
                match RemoteRun::new(RemoteRunTarget::Nodes(vec![node_id]), &simple_map) {
                    Ok(handle) => handle.run(job_config2.clone()),
                    Err(e) => Err(warp::reject::custom(Error::InvalidCondition(e.to_string()))),
                }
            }),
    );

    let job_config3 = job_config.clone();
    let nodes = warp::path("nodes").and(warp::path::end().and(warp::body::form()).and_then(
        move |simple_map: HashMap<String, String>| match simple_map.get("nodes") {
            Some(x) => match RemoteRun::new(
                RemoteRunTarget::Nodes(
                    x.split(',').map(|s| s.to_string()).collect::<Vec<String>>(),
                ),
                &simple_map,
            ) {
                Ok(x) => x.run(job_config3.clone()),
                Err(x) => Err(warp::reject::custom(Error::InvalidCondition(x.to_string()))),
            },

            None => Err(warp::reject::custom(Error::MissingTargetNodes)),
        },
    ));

    let job_config4 = job_config.clone();
    let all = warp::path("all").and(warp::body::form()).and_then(
        move |simple_map: HashMap<String, String>| match RemoteRun::new(
            RemoteRunTarget::All,
            &simple_map,
        ) {
            Ok(handle) => handle.run(job_config4.clone()),
            Err(e) => Err(warp::reject::custom(Error::InvalidCondition(e.to_string()))),
        },
    );

    let shared_files_put = warp::path::peek().and(warp::body::form()).map(
        move |peek: warp::filters::path::Peek, simple_map: HashMap<String, String>| {
            let metadata = Metadata::new(simple_map);
            metadata_writer(format!("{}", metadata.unwrap()), peek.as_str());
            warp::reply()
        },
    );

    let shared_files_head = warp::path::peek()
        .and(warp::filters::query::raw()) // recuperation du parametre ?hash=file-hash
        .map(|peek: warp::filters::path::Peek, raw: String| {
            warp::reply::with_status(
                "".to_string(),
                metadata_hash_checker(format!("./{}", peek.as_str()), parse_hash_from_raw(raw)),
            )
        });

    let routes = warp::get2()
        .and(status.or(stats_simple))
        .or(warp::post2().and(remote_run).and(nodes.or(all).or(node_id)))
        .or(warp::put2().and(shared_files).and(shared_files_put))
        .or(warp::head().and(shared_files).and(shared_files_head))
        .with(warp::log("relayd::relay-api"));

    let (addr, server) = warp::serve(routes).bind_with_graceful_shutdown(listen, shutdown);
    info!("Started API on {}", addr);
    server
}
