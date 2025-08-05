// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use std::{
    fmt,
    fmt::Display,
    net::ToSocketAddrs,
    path::{Path, PathBuf},
    sync::Arc,
};

use anyhow::{bail, Error};
use percent_encoding::percent_decode_str;
use serde::Serialize;
use tracing::{error, info, instrument};
use warp::{http::StatusCode, path, reject, reject::Reject, reply, Filter, Rejection, Reply};

use crate::JobConfig;

mod metrics;
mod remote_run;
mod shared_files;
mod shared_folder;
mod system;

#[derive(Debug)]
struct RudderReject {
    reason: String,
}

impl Display for RudderReject {
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

#[instrument(name = "api", level = "debug", skip(job_config))]
pub async fn run(job_config: Arc<JobConfig>) -> Result<(), ()> {
    let routes_1 = path!("rudder" / "relay-api" / "1" / ..)
        .and(
            system::routes_1(job_config.clone())
                .or(shared_folder::routes_1(job_config.clone()))
                .or(shared_files::routes_1(job_config.clone()))
                .or(remote_run::routes_1(job_config.clone())),
            /* special case for /metrics which is the standard URL
             * with no versioning */
        )
        .or(metrics::routes());

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
    warp::serve(routes).run(socket).await;
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
        Ok(reply::with_status(format!("{e}"), StatusCode::BAD_REQUEST))
    } else if let Some(_e) = reject.find::<reject::MethodNotAllowed>() {
        // TODO find why we only have MethodNotAllowed when file in found in fs::dir
        Ok(reply::with_status(
            "NOT FOUND".to_string(),
            StatusCode::NOT_FOUND,
        ))
    } else {
        Ok(reply::with_status(
            format!("{reject:?}"),
            StatusCode::INTERNAL_SERVER_ERROR,
        ))
    }
}

/// Adapted from warp: https://github.com/seanmonstar/warp/blob/376c80528fbf783dfb2825f8686698c3a51ac6d4/src/filters/fs.rs#L111
fn sanitize_path(base: impl AsRef<Path>, tail: &str) -> Result<PathBuf, Error> {
    let mut buf = PathBuf::from(base.as_ref());
    let p = match percent_decode_str(tail).decode_utf8() {
        Ok(p) => p,
        Err(err) => {
            bail!("Failed to decode path={:?}: {:?}", tail, err)
        }
    };
    tracing::trace!("dir? base={:?}, route={:?}", base.as_ref(), p);
    for seg in p.split('/') {
        if seg.starts_with("..") {
            bail!(
                "Rejecting path={:?}: rejecting segment starting with '..'",
                tail
            )
        } else if seg.contains('\\') {
            bail!(
                "Rejecting path={:?}: rejecting segment containing backslash (\\)",
                tail
            )
        } else if cfg!(windows) && seg.contains(':') {
            bail!(
                "Rejecting path={:?}: rejecting segment containing colon (:)",
                tail
            )
        } else {
            buf.push(seg);
        }
    }
    Ok(buf)
}

#[cfg(test)]
mod tests {
    use anyhow::Error;

    use super::*;
    use crate::error::RudderError;

    #[test]
    fn it_sanitizes_path() {
        let base = "tests/sanitize_path";
        assert_eq!(
            sanitize_path(base, "folder1/file1")
                .unwrap()
                .to_str()
                .unwrap(),
            "tests/sanitize_path/folder1/file1"
        );
        assert!(sanitize_path(base, "folder1/../../../file1").is_err());
    }

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
