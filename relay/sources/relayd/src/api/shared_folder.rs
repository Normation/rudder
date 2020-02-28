// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use crate::{error::Error, hashing::Hash, JobConfig};
use futures::{future, Future};
use serde::Deserialize;
use std::{io, path::PathBuf, sync::Arc};
use tokio::fs::read;
use tracing::{debug, span, trace, Level};
use warp::http::StatusCode;

#[derive(Deserialize, Debug)]
pub struct SharedFolderParams {
    #[serde(default)]
    hash: String,
    #[serde(default = "default_hash")]
    hash_type: String,
}
fn default_hash() -> String {
    "sha256".to_string()
}

impl SharedFolderParams {
    fn hash(self) -> Result<Option<Hash>, Error> {
        if self.hash.is_empty() {
            Ok(None)
        } else {
            Hash::new(self.hash_type, self.hash).map(Some)
        }
    }
}

pub fn head(
    params: SharedFolderParams,
    // Relative path
    file: PathBuf,
    job_config: Arc<JobConfig>,
) -> impl Future<Item = StatusCode, Error = Error> + Send {
    let span = span!(
        Level::INFO,
        "shared_folder_head",
        file = %file.display(),
    );
    let _enter = span.enter();

    let file_path = job_config.cfg.shared_folder.path.join(&file);
    debug!(
        "Received request for {:#} ({:#} locally) with the following parameters: {:?}",
        file.display(),
        file_path.display(),
        params
    );

    future::result(params.hash()).and_then(move |hash| {
        // TODO do not read entire file into memory
        read(file_path).then(move |res| match res {
            Ok(data) => match hash {
                None => {
                    debug!("{} exists and no hash was provided", file.display());
                    future::ok(StatusCode::OK)
                }
                Some(h) => {
                    let actual_hash = h.hash_type.hash(&data);
                    trace!("{} has hash '{}'", file.display(), actual_hash);
                    if h == actual_hash {
                        debug!("{} exists and has same hash", file.display());
                        future::ok(StatusCode::NOT_MODIFIED)
                    } else {
                        debug!("{} exists but its hash is different", file.display());
                        future::ok(StatusCode::OK)
                    }
                }
            },
            Err(ref e) if e.kind() == io::ErrorKind::NotFound => {
                debug!("{} does not exist on the server", file.display());
                future::ok(StatusCode::NOT_FOUND)
            }
            Err(e) => future::err(e.into()),
        })
    })
}
