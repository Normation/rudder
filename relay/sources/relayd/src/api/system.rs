// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use crate::{
    api::{ApiResponse, ApiResult},
    check_configuration,
    output::database::ping,
    Error, JobConfig,
};
use serde::Serialize;
use std::sync::Arc;
use structopt::clap::crate_version;
use warp::{filters::method, path, Filter, Reply};

pub fn routes_1(
    job_config: Arc<JobConfig>,
) -> impl Filter<Extract = impl Reply, Error = warp::Rejection> + Clone {
    let base = path!("system" / ..);

    let info = method::get().and(base).and(path!("info")).map(|| {
        Ok(ApiResponse::new::<Error>("getSystemInfo", Ok(Some(Info::new())), None).reply())
    });

    let job_config_reload = job_config.clone();
    let reload = method::post()
        .and(base)
        .and(path!("reload"))
        .map(move || job_config_reload.clone())
        .and_then(handlers::reload);

    let job_config_status = job_config;
    let status = method::get().and(base).and(path!("status")).map(move || {
        Ok(ApiResponse::new::<Error>(
            "getStatus",
            Ok(Some(Status::poll(job_config_status.clone()))),
            None,
        )
        .reply())
    });

    info.or(reload).or(status)
}

pub mod handlers {
    use super::*;
    use warp::{Rejection, Reply};

    pub async fn reload(job_config: Arc<JobConfig>) -> Result<impl Reply, Rejection> {
        Ok(ApiResponse::<()>::new::<Error>(
            "reloadConfiguration",
            job_config.reload().await.map(|_| None),
            None,
        )
        .reply())
    }
}

// TODO could be in once_cell
#[derive(Serialize, Debug, PartialEq, Eq)]
#[serde(rename_all = "kebab-case")]
struct Info {
    pub major_version: String,
    pub full_version: String,
}

impl Info {
    pub fn new() -> Self {
        Info {
            major_version: format!(
                "{}.{}",
                env!("CARGO_PKG_VERSION_MAJOR"),
                env!("CARGO_PKG_VERSION_MINOR")
            ),
            full_version: crate_version!().to_string(),
        }
    }
}
#[derive(Serialize, Debug, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
struct State {
    status: ApiResult,
    #[serde(skip_serializing_if = "Option::is_none")]
    details: Option<String>,
}

impl From<Result<(), Error>> for State {
    fn from(result: Result<(), Error>) -> Self {
        match result {
            Ok(()) => State {
                status: ApiResult::Success,
                details: None,
            },
            Err(e) => State {
                status: ApiResult::Error,
                details: Some(e.to_string()),
            },
        }
    }
}

#[derive(Serialize, Debug, PartialEq, Eq)]
struct Status {
    #[serde(skip_serializing_if = "Option::is_none")]
    database: Option<State>,
    configuration: State,
}

impl Status {
    pub fn poll(job_config: Arc<JobConfig>) -> Self {
        Self {
            database: job_config
                .pool
                .clone()
                .map(|p| ping(&p).map_err(|e| e).into()),
            configuration: check_configuration(&job_config.cli_cfg.configuration_dir)
                .map_err(|e| e)
                .into(),
        }
    }
}
