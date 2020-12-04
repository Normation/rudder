// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

mod remote_run;
mod shared_files;
mod shared_folder;
mod system;

use crate::{stats::Stats, JobConfig};
use serde::Serialize;
use std::{
    fmt,
    fmt::Display,
    net::ToSocketAddrs,
    sync::{Arc, RwLock},
};
use tracing::{error, info, span, Level};
use warp::{http::StatusCode, path, reject, reject::Reject, reply, Filter, Rejection, Reply};

#[derive(Debug)]
struct RudderReject {
    reason: String,
}

impl fmt::Display for RudderReject {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.reason)
    }
}

impl RudderReject {
    pub fn new<T: Display>(reason: T) -> Self {
        Self {
            reason: reason.to_string(),
        }
    }
}

impl Reject for RudderReject {}

#[derive(Serialize, Debug, PartialEq, Eq, Clone)]
#[serde(rename_all = "lowercase")]
pub enum ApiResult {
    Success,
    Error,
}

#[derive(Serialize, Debug, PartialEq, Eq, Clone)]
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

pub async fn run(job_config: Arc<JobConfig>, stats: Arc<RwLock<Stats>>) -> Result<(), ()> {
    let span = span!(Level::TRACE, "api");
    let _enter = span.enter();

    let routes_1 = path!("rudder" / "relay-api" / "1" / ..).and(
        system::routes_1(job_config.clone(), stats.clone())
            .or(shared_folder::routes_1(job_config.clone()))
            .or(shared_files::routes_1(job_config.clone()))
            .or(remote_run::routes_1(job_config.clone())),
    );

    let routes = routes_1
        .recover(customize_error)
        .with(warp::log("relayd::api"));

    let listen = &job_config.cfg.general.listen;
    info!("Starting API on {}", listen);

    let mut addresses = listen.to_socket_addrs().map_err(|e| {
        // Log resolution error
        error!("{}", e);
    })?;
    // Use first resolved address for now
    let socket = addresses.next().unwrap();
    warp::serve(routes).bind(socket).await;
    Ok(())
}

async fn customize_error(reject: Rejection) -> Result<impl Reply, Rejection> {
    // See https://github.com/seanmonstar/warp/issues/77
    // We override the priority to avoid MethodNotAllowed everywhere
    if reject.is_not_found() {
        Ok(reply::with_status(
            "NOT FOUND".to_string(),
            StatusCode::NOT_FOUND,
        ))
    } else if let Some(e) = reject.find::<RudderReject>() {
        Ok(reply::with_status(
            format!("{}", e),
            StatusCode::BAD_REQUEST,
        ))
    } else if let Some(_e) = reject.find::<reject::MethodNotAllowed>() {
        // TODO find why we only have MethodNotAllowed when file in found in fs::dir
        Ok(reply::with_status(
            "NOT FOUND".to_string(),
            StatusCode::NOT_FOUND,
        ))
    } else {
        Ok(reply::with_status(
            format!("{:?}", reject),
            StatusCode::INTERNAL_SERVER_ERROR,
        ))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::error::RudderError;
    use anyhow::Error;

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
                Err(RudderError::InconsistentRunlog.into()),
                None
            ))
            .unwrap(),
            "{\"result\":\"error\",\"action\":\"actionName3\",\"errorDetails\":\"inconsistent run log\"}".to_string()
        );
    }
}
