// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use crate::{configuration::Secret, processing::inventory::InventoryType, Error, JobConfig};
use futures::Future;
use std::{path::PathBuf, sync::Arc};
use tracing::{debug, span, Level};

pub fn send_report(
    job_config: Arc<JobConfig>,
    path: PathBuf,
) -> Box<dyn Future<Item = (), Error = Error> + Send> {
    let report_span = span!(Level::TRACE, "upstream");
    let _report_enter = report_span.enter();
    Box::new(forward_file(
        job_config.clone(),
        "reports",
        path,
        job_config.cfg.output.upstream.password.clone(),
    ))
}

pub fn send_inventory(
    job_config: Arc<JobConfig>,
    path: PathBuf,
    inventory_type: InventoryType,
) -> Box<dyn Future<Item = (), Error = Error> + Send> {
    let report_span = span!(Level::TRACE, "upstream");
    let _report_enter = report_span.enter();
    Box::new(forward_file(
        job_config.clone(),
        match inventory_type {
            InventoryType::New => "inventories",
            InventoryType::Update => "inventory-updates",
        },
        path,
        match inventory_type {
            InventoryType::New => job_config.cfg.output.upstream.default_password.clone(),
            InventoryType::Update => job_config.cfg.output.upstream.password.clone(),
        },
    ))
}

fn forward_file(
    job_config: Arc<JobConfig>,
    endpoint: &str,
    path: PathBuf,
    password: Secret,
) -> impl Future<Item = (), Error = Error> + '_ {
    tokio::fs::read(path.clone())
        .map_err(|e| e.into())
        .and_then(move |d| {
            job_config
                .client
                .clone()
                .put(&format!(
                    "{}/{}/{}",
                    job_config.cfg.output.upstream.url,
                    endpoint,
                    path.file_name().expect("not a file").to_string_lossy()
                ))
                .basic_auth(
                    &job_config.cfg.output.upstream.user,
                    Some(&password.value()),
                )
                .body(d)
                .send()
                // HTTP error -> Err()
                .and_then(|r| r.error_for_status())
                .map(|r| debug!("Server response: {:#?}", r))
                .map_err(|e| e.into())
        })
}
