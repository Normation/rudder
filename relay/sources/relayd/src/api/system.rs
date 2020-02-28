// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use crate::{api::ApiResult, check_configuration, output::database::ping, Error, JobConfig};
use serde::Serialize;
use std::sync::Arc;
use structopt::clap::crate_version;

#[derive(Serialize, Debug, PartialEq, Eq)]
#[serde(rename_all = "kebab-case")]
pub struct Info {
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
pub struct State {
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
pub struct Status {
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
