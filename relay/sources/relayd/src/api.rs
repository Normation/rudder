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
    shared_files::{self, metadata_parser, SharedFilesHeadParams, SharedFilesPutParams},
    shared_folder::{self, SharedFolderParams},
    stats::Stats,
    status::Status,
    JobConfig,
};
use futures::Future;
use std::{
    collections::HashMap,
    net::SocketAddr,
    path::Path,
    sync::{Arc, RwLock},
};
use tracing::info;
use warp::{
    body::{self, FullBody},
    filters::{method::v2::*, path::Peek},
    http::StatusCode,
    path, query,
    reject::custom,
    reply, Buf, Filter,
};

pub fn api(
    listen: SocketAddr,
    shutdown: impl Future<Item = ()> + Send + 'static,
    job_config: Arc<JobConfig>,
    stats: Arc<RwLock<Stats>>,
) -> impl Future<Item = (), Error = ()> {
    let stats = get()
        .and(path("stats"))
        .map(move || reply::json(&(*stats.clone().read().expect("open stats database"))));

    let job_config0 = job_config.clone();
    // TODO test error case
    let reload = post()
        .and(path("reload"))
        .map(move || reply::json(&job_config0.clone().reload().map_err(custom)));

    let job_config1 = job_config.clone();
    let status = get()
        .and(path("status"))
        .map(move || reply::json(&Status::poll(job_config1.clone())));

    let job_config2 = job_config.clone();
    let node_id =
        post()
            .and(path("nodes"))
            .and(path::param::<String>().and(body::form()).and_then(
                move |node_id, simple_map: HashMap<String, String>| match RemoteRun::new(
                    RemoteRunTarget::Nodes(vec![node_id]),
                    &simple_map,
                ) {
                    Ok(handle) => handle.run(job_config2.clone()),
                    Err(e) => Err(custom(Error::InvalidCondition(e.to_string()))),
                },
            ));

    let job_config3 = job_config.clone();
    let nodes =
        post()
            .and(path("nodes"))
            .and(path::end().and(body::form()).and_then(
                move |simple_map: HashMap<String, String>| match simple_map.get("nodes") {
                    Some(x) => match RemoteRun::new(
                        RemoteRunTarget::Nodes(
                            x.split(',').map(|s| s.to_string()).collect::<Vec<String>>(),
                        ),
                        &simple_map,
                    ) {
                        Ok(x) => x.run(job_config3.clone()),
                        Err(x) => Err(custom(Error::InvalidCondition(x.to_string()))),
                    },

                    None => Err(custom(Error::MissingTargetNodes)),
                },
            ));

    let job_config4 = job_config.clone();
    let all = post().and(path("all")).and(body::form()).and_then(
        move |simple_map: HashMap<String, String>| match RemoteRun::new(
            RemoteRunTarget::All,
            &simple_map,
        ) {
            Ok(handle) => handle.run(job_config4.clone()),
            Err(e) => Err(custom(Error::InvalidCondition(e.to_string()))),
        },
    );

    let job_config5 = job_config.clone();
    let shared_files_put = put()
        .and(path::param::<String>())
        .and(path::param::<String>())
        .and(path::param::<String>())
        .and(query::<SharedFilesPutParams>())
        .and(body::concat())
        .map(
            move |target_id,
                  source_id,
                  file_id,
                  params: SharedFilesPutParams,
                  mut buf: FullBody| {
                reply::with_status(
                    "".to_string(),
                    match shared_files::put(
                        // FIXME avoid parsing metadata twice!
                        // Warning, this reads inside of file content too for
                        // metadata k/v
                        format!("{}", metadata_parser(buf.by_ref()).unwrap()),
                        target_id,
                        source_id,
                        file_id,
                        params,
                        job_config5.clone(),
                        buf,
                    ) {
                        Ok(x) => x,
                        Err(_x) => StatusCode::INTERNAL_SERVER_ERROR,
                    },
                )
            },
        );

    let job_config6 = job_config.clone();
    let shared_files_head = head()
        .and(path::param::<String>())
        .and(path::param::<String>())
        .and(path::param::<String>())
        .and(query::<SharedFilesHeadParams>())
        .map(move |target_id, source_id, file_id, params| {
            reply::with_status(
                "".to_string(),
                match shared_files::head(target_id, source_id, file_id, params, job_config6.clone())
                {
                    Ok(x) => x,
                    Err(_x) => StatusCode::INTERNAL_SERVER_ERROR,
                },
            )
        });

    let job_config7 = job_config.clone();
    let shared_folder = head()
        .and(path::peek())
        .and(query::<SharedFolderParams>())
        .map(move |file: Peek, params| {
            match shared_folder::head(params, Path::new(&file.as_str()), job_config7.clone()) {
                Ok(reply) => reply,
                Err(e) => reply::with_status(e.to_string(), StatusCode::INTERNAL_SERVER_ERROR),
            }
        });

    // Routing

    // /rudder/relay-ctl/
    let relay_ctl = path("relay-ctl").and((stats.or(status)).or(reload));

    // /rudder/relay-api/
    let remote_run = path("remote-run").and(nodes.or(all).or(node_id));
    let shared_files = path("shared-files").and((shared_files_put).or(shared_files_head));
    // GET is handled directly by httpd
    let shared_folder = path("shared-folder").and(shared_folder);
    let relay_api = path("relay-api").and(remote_run.or(shared_files).or(shared_folder));

    // global route
    let routes = path("rudder")
        .and(relay_ctl.or(relay_api))
        .with(warp::log("relayd::relay-api"));

    let (addr, server) = warp::serve(routes).bind_with_graceful_shutdown(listen, shutdown);
    info!("Started API on {}", addr);
    server
}
