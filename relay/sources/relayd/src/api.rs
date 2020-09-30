// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

mod remote_run;
mod shared_files;
mod shared_folder;
mod system;

use crate::{
    api::{
        remote_run::{RemoteRun, RemoteRunTarget},
        shared_files::{SharedFilesHeadParams, SharedFilesPutParams},
        shared_folder::SharedFolderParams,
        system::{Info, Status},
    },
    error::Error,
    stats::Stats,
    JobConfig,
};
use futures::{future, Future};
use serde::Serialize;
use std::{
    collections::HashMap,
    fmt::Display,
    net::{SocketAddr, ToSocketAddrs},
    path::PathBuf,
    sync::{Arc, RwLock},
};
use tracing::{error, info, span, Level};
use warp::{
    body::{self, FullBody},
    filters::{method::v2::*, path::Peek},
    fs,
    http::StatusCode,
    path, query,
    reject::custom,
    reply, Filter, Rejection, Reply,
};

#[derive(Serialize, Debug, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum ApiResult {
    Success,
    Error,
}

#[derive(Serialize, Debug, PartialEq, Eq)]
pub struct ApiResponse<T: Serialize> {
    #[serde(skip_serializing_if = "Option::is_none")]
    data: Option<T>,
    result: ApiResult,
    action: &'static str,
    #[serde(rename = "errorDetails")]
    #[serde(skip_serializing_if = "Option::is_none")]
    error_details: Option<String>,
    #[serde(skip)]
    status_code: StatusCode,
}

impl<T: Serialize> ApiResponse<T> {
    fn new<E: Display>(
        action: &'static str,
        data: Result<Option<T>, E>,
        status_code: Option<StatusCode>,
    ) -> Self {
        match data {
            Ok(Some(d)) => ApiResponse {
                data: Some(d),
                result: ApiResult::Success,
                action,
                error_details: None,
                status_code: status_code.unwrap_or(StatusCode::OK),
            },
            Ok(None) => ApiResponse {
                data: None,
                result: ApiResult::Success,
                action,
                error_details: None,
                status_code: status_code.unwrap_or(StatusCode::OK),
            },
            Err(e) => ApiResponse {
                data: None,
                result: ApiResult::Error,
                action,
                error_details: Some(e.to_string()),
                status_code: status_code.unwrap_or(StatusCode::INTERNAL_SERVER_ERROR),
            },
        }
    }

    fn reply(&self) -> impl Reply {
        reply::with_status(reply::json(self), self.status_code)
    }
}

pub fn run(
    listen: &str,
    job_config: Arc<JobConfig>,
    stats: Arc<RwLock<Stats>>,
) -> impl Future<Item = (), Error = ()> {
    let span = span!(Level::TRACE, "api");
    let _enter = span.enter();

    // WARNING: Not stable, will be replaced soon
    // Kept for testing mainly
    let stats = get()
        .and(path("stats"))
        .map(move || reply::json(&(*stats.clone().read().expect("open stats database"))));

    // New endpoints, following Rudder's API format
    let info = get().and(path("info")).map(move || {
        ApiResponse::new::<Error>("getSystemInfo", Ok(Some(Info::new())), None).reply()
    });

    let job_config0 = job_config.clone();
    let reload = post().and(path("reload")).map(move || {
        ApiResponse::<()>::new::<Error>(
            "reloadConfiguration",
            job_config0.clone().reload().map(|_| None),
            None,
        )
        .reply()
    });

    let job_config1 = job_config.clone();
    let status = get().and(path("status")).map(move || {
        ApiResponse::new::<Error>(
            "getStatus",
            Ok(Some(Status::poll(job_config1.clone()))),
            None,
        )
        .reply()
    });

    // Old compatible endpoints

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
                    Err(e) => Err(custom(e.to_string())),
                },
            ));

    let job_config3 = job_config.clone();
    let nodes =
        post()
            .and(path("nodes"))
            .and(path::end().and(body::form()).and_then(
                move |simple_map: HashMap<String, String>| {
                    match simple_map.get("nodes") {
                        Some(nodes) => match RemoteRun::new(
                            RemoteRunTarget::Nodes(
                                nodes
                                    .split(',')
                                    .map(|s| s.to_string())
                                    .collect::<Vec<String>>(),
                            ),
                            &simple_map,
                        ) {
                            Ok(handle) => handle.run(job_config3.clone()),
                            Err(e) => Err(custom(e.to_string())),
                        },
                        None => Err(custom(Error::MissingTargetNodes)),
                    }
                },
            ));

    let job_config4 = job_config.clone();
    let all = post().and(path("all")).and(body::form()).and_then(
        move |simple_map: HashMap<String, String>| match RemoteRun::new(
            RemoteRunTarget::All,
            &simple_map,
        ) {
            Ok(handle) => handle.run(job_config4.clone()),
            Err(e) => Err(custom(e.to_string())),
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
            move |target_id, source_id, file_id, params: SharedFilesPutParams, buf: FullBody| {
                reply::with_status(
                    "".to_string(),
                    match shared_files::put(
                        target_id,
                        source_id,
                        file_id,
                        params,
                        job_config5.clone(),
                        buf,
                    ) {
                        Ok(x) => x,
                        Err(e) => {
                            error!("error while processing request: {}", e);
                            StatusCode::INTERNAL_SERVER_ERROR
                        }
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
                    Err(e) => {
                        error!("error while processing request: {}", e);
                        StatusCode::INTERNAL_SERVER_ERROR
                    }
                },
            )
        });

    let job_config7 = job_config.clone();
    let shared_folder_head = head()
        .and(path::peek())
        .and(query::<SharedFolderParams>())
        .and_then(move |file: Peek, params| {
            shared_folder::head(params, PathBuf::from(&file.as_str()), job_config7.clone())
                .map(|c| reply::with_status("".to_string(), c))
                .map_err(|e| {
                    error!("{}", e);
                    warp::reject::custom(e)
                })
        });
    let shared_folder_get = fs::dir(job_config.cfg.shared_folder.path.clone());

    // Routing
    // // /api/ for public API, /relay-api/ for internal relay API
    let base = path("rudder").and(path("relay-api"));
    let system = path("system").and(stats.or(status).or(reload).or(info));
    let remote_run = path("remote-run").and(nodes.or(all).or(node_id));
    let shared_files = path("shared-files").and((shared_files_put).or(shared_files_head));
    let shared_folder = path("shared-folder").and(shared_folder_head.or(shared_folder_get));

    // Global route for /1/
    let routes_1 = base
        .and(path("1"))
        .and(system.or(remote_run).or(shared_files).or(shared_folder))
        .recover(customize_error)
        .with(warp::log("relayd::relay-api"));

    info!("Starting API on {}", listen);
    // TODO graceful shutdown
    future::result(
        listen
            .to_socket_addrs()
            .map_err(|e| {
                // Log resolution error
                error!("{}", e);
            })
            // Use first resolved address for now
            .and_then(|mut a| a.next().ok_or(())),
    )
    .and_then(|s: SocketAddr| warp::serve(routes_1).bind(s))
}

fn customize_error(reject: Rejection) -> Result<impl Reply, Rejection> {
    // See https://github.com/seanmonstar/warp/issues/77
    // We generally prefer 404 to 405 when they are conflicting.
    // Maybe be improved in the future
    if reject.is_not_found() || reject.status() == StatusCode::METHOD_NOT_ALLOWED {
        Ok(reply::with_status("", StatusCode::NOT_FOUND))
    } else {
        Err(reject)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn it_serializes_api_response() {
        assert_eq!(
            serde_json::to_string(&ApiResponse::<()>::new::<Error>(
                "actionName1",
                Ok(None),
                None
            ))
            .unwrap(),
            "{\"result\":\"success\",\"action\":\"actionName1\"}".to_string()
        );
        assert_eq!(
            serde_json::to_string(&ApiResponse::new::<Error>(
                "actionName2",
                Ok(Some("thing".to_string())),
                None
            ))
            .unwrap(),
            "{\"data\":\"thing\",\"result\":\"success\",\"action\":\"actionName2\"}".to_string()
        );
        assert_eq!(
            serde_json::to_string(&ApiResponse::<()>::new::<Error>(
                "actionName3",
                Err(Error::InconsistentRunlog),
                None
            ))
            .unwrap(),
            "{\"result\":\"error\",\"action\":\"actionName3\",\"errorDetails\":\"inconsistent run log\"}".to_string()
        );
    }
}
