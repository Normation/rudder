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

extern crate data_encoding;
use crate::error::Error;
use crate::remote_run::{nodes_handle, nodes_handle2, AgentParameters, RemoteRun, RemoteRunTarget};
use crate::shared_files::{
    metadata_hash_checker,
    metadata_writer,
    parse_hash_from_raw,
    parse_path_from_peek,
    parse_ttl,
    Metadata, // Digest, Sha1, Sha256, Sha512
};
use crate::{configuration::LogComponent, stats::Stats, status::Status, JobConfig};
use futures::Future;
use std::collections::HashMap;
use std::str::FromStr;
use std::{fs, io};
use std::{
    net::SocketAddr,
    sync::{Arc, RwLock},
};
use tracing::info;
use warp::Filter;
pub extern crate sha1;
use sha1::Sha1;
use std::env;
extern crate sha2;
use sha2::{Digest, Sha256, Sha512};
use std::fs::File;
use std::io::{BufReader, Read, Write};

pub fn api(
    listen: SocketAddr,
    shutdown: impl Future<Item = ()> + Send + 'static,
    job_config: Arc<JobConfig>,
    stats: Arc<RwLock<Stats>>,
) -> impl Future<Item = (), Error = ()> {
    let job_config2 = job_config.clone();
    let job_config3 = job_config.clone();
    let stats_simple = warp::path("stats").map(move || {
        info!("/stats queried");
        warp::reply::json(&(*stats.clone().read().unwrap()))
    });

    let status = warp::path("status").map(move || {
        info!("/status queried");
        warp::reply::json(&Status::poll(job_config.clone()))
    });

    let nodes = warp::path("nodes").and(warp::path::end().and(warp::body::form()).and_then(
        move |simple_map: HashMap<String, String>| match nodes_handle(
            &simple_map,
            "nodes".to_string(),
        ) {
            Ok(handle) => nodes_handle2(&handle, job_config2.clone()),
            Err(e) => Err(warp::reject::custom(Error::InvalidCondition(e.to_string()))),
        },
    ));

    let node_id = warp::path("nodes").and(warp::path::param::<String>().map(|node| {
        info!("remote run triggered on node {}", node);
        warp::reply()
    }));

    let all = warp::path("all").and(warp::body::form()).and_then(
        move |simple_map: HashMap<String, String>| match nodes_handle(
            &simple_map,
            "all".to_string(),
        ) {
            Ok(handle) => nodes_handle2(&handle, job_config3.clone()),
            Err(e) => Err(warp::reject::custom(Error::InvalidCondition(e.to_string()))),
        },
    );

    let rudder = warp::path("rudder");
    let relay_api = warp::path("relay-api");
    let remote_run = warp::path("remote-run");

    let shared_files = warp::path("shared-files")
        .and(warp::path::peek())
        .and(warp::body::form()) // recuperation du body
        .map(
            |peek: warp::filters::path::Peek, simple_map: HashMap<String, String>| {
                // info!("SHARED FILES {:?}", peek; "component" => LogComponent::Statistics);

                // info!("{:?}", &simple_map; "component" => LogComponent::Statistics);

                info!("METADATA : {}", metadata_writer(simple_map, peek));

                //info!("{:?}", parse_ttl(simple_map.get("ttl").unwrap()); "component" => LogComponent::Statistics);
            warp::reply()

            },
        );

    let shared_files_head = warp::path("shared-files") // recuperation du path OK
        .and(warp::path::peek()) // recuperation de <target-uuid> / <source-uuid> / <file-id>
        .and(warp::filters::query::raw()) // recuperation du parametre ?hash=file-hash
        .map(|peek: warp::filters::path::Peek, raw: String| {
            let path = parse_path_from_peek(peek);

            // let mut file = fs::File::open("./lalal/lolo/lili").unwrap();
            // let mut hasher = Sha256::new();
            // let n = io::copy(&mut file, &mut hasher);
            // let hash = hasher.result();
            // info!("{:x}", hash; "component" => LogComponent::Statistics);

            let contents = fs::read_to_string("./metadata_test.txt")
                .expect("Something went wrong reading the file");

            let mymeta = Metadata::from_str(&contents);

            info!("{:?}", mymeta);

            warp::reply::with_status(
                "".to_string(),
                metadata_hash_checker("./metadata_test.txt".to_string(), parse_hash_from_raw(raw)),
            )
        });

    let routes = warp::get2()
        .and(status.or(stats_simple))
        .or(warp::post2()
            .and(rudder)
            .and(relay_api)
            .and(remote_run)
            .and(nodes.or(all).or(node_id)))
        .or(warp::put2().and(shared_files))
        .or(warp::head().and(shared_files_head));

    let (addr, server) = warp::serve(routes).bind_with_graceful_shutdown(listen, shutdown);
    info!("Started stats API on {}", addr);
    server
}
