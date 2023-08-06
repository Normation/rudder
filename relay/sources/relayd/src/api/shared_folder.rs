// SPDX-License-Identifier: GPL-3.0-or-later WITH GPL-3.0-linking-source-exception
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use std::{io, sync::Arc};

use anyhow::Error;
use serde::Deserialize;
use tokio::fs::read;
use tracing::{debug, error, instrument, trace};
use warp::{
    filters::{method, BoxedFilter},
    fs,
    http::StatusCode,
    path, query, Filter, Reply,
};

use crate::api::sanitize_path;
use crate::{api::RudderReject, hashing::Hash, JobConfig};

pub fn routes_1(job_config: Arc<JobConfig>) -> BoxedFilter<(impl Reply,)> {
    let base = path!("shared-folder" / ..);

    let job_config_head = job_config.clone();
    let head = method::head()
        .and(base)
        .map(move || job_config_head.clone())
        .and(path::peek())
        .and(query::<SharedFolderParams>())
        .and_then(|j, p, q| handlers::head(p, q, j));

    let job_config_get = job_config;
    // specify get to avoid serving HEAD requests which have a specific handler
    let get = method::get()
        .and(base)
        // build-in method to serve static file in dir
        .and(fs::dir(job_config_get.cfg.shared_folder.path.clone()));

    head.or(get).boxed()
}

pub mod handlers {
    use warp::{filters::path::Peek, reject, reply, Rejection, Reply};

    use crate::JobConfig;

    use super::*;

    pub async fn head(
        file: Peek,
        params: SharedFolderParams,
        job_config: Arc<JobConfig>,
    ) -> Result<impl Reply, Rejection> {
        let file = file.as_str();
        super::head(params, file, job_config.clone())
            .await
            .map(|c| reply::with_status("".to_string(), c))
            .map_err(|e| {
                error!("{}", e);
                reject::custom(RudderReject::new(e))
            })
    }
}

#[derive(Deserialize, Debug)]
pub struct SharedFolderParams {
    #[serde(default)]
    hash: String,
    #[serde(default)]
    hash_type: String,
}

impl SharedFolderParams {
    fn hash(self) -> Result<Option<Hash>, Error> {
        if self.hash.is_empty() {
            Ok(None)
        } else if self.hash_type.is_empty() {
            Hash::new("sha256", &self.hash).map(Some)
        } else {
            Hash::new(&self.hash_type, &self.hash).map(Some)
        }
    }
}

#[instrument(name = "shared_folder_head", level = "debug", skip(job_config))]
pub async fn head(
    params: SharedFolderParams,
    file: &str,
    job_config: Arc<JobConfig>,
) -> Result<StatusCode, Error> {
    let file_path = sanitize_path(&job_config.cfg.shared_folder.path, file)?;
    debug!(
        "Received request for {:#} ({:#} locally) with the following parameters: {:?}",
        file,
        file_path.display(),
        params
    );

    // TODO do not read entire file into memory
    match read(&file_path).await {
        Ok(data) => match params.hash()? {
            None => {
                debug!("{} exists and no hash was provided", file_path.display());
                Ok(StatusCode::OK)
            }
            Some(h) => {
                let actual_hash = h.hash_type.hash(&data);
                trace!("{} has hash '{}'", file_path.display(), actual_hash);
                if h == actual_hash {
                    debug!("{} exists and has same hash", file_path.display());
                    Ok(StatusCode::NOT_MODIFIED)
                } else {
                    debug!("{} exists but its hash is different", file_path.display());
                    Ok(StatusCode::OK)
                }
            }
        },
        Err(ref e) if e.kind() == io::ErrorKind::NotFound => {
            debug!("{} does not exist on the server", file_path.display());
            Ok(StatusCode::NOT_FOUND)
        }
        Err(e) => Err(e.into()),
    }
}
