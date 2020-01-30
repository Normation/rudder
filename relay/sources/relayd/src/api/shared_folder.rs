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

use crate::{error::Error, hashing::HashType, JobConfig};
use futures::{future, Future};
use serde::Deserialize;
use std::{io, path::PathBuf, str::FromStr, sync::Arc};
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

    future::result(HashType::from_str(&params.hash_type)).and_then(move |hash_type| {
        read(file_path).then(move |res| match res {
            Ok(data) => {
                if params.hash.is_empty() {
                    debug!("{} exists and no hash was provided", file.display());
                    return future::ok(StatusCode::OK);
                }

                let hash = hash_type.hash(&data);
                trace!("{} has {} hash '{}'", file.display(), hash_type, hash);
                if hash == params.hash {
                    debug!("{} exists and has same hash", file.display());
                    future::ok(StatusCode::NOT_MODIFIED)
                } else {
                    debug!("{} exists but its hash is different", file.display());
                    future::ok(StatusCode::OK)
                }
            }
            Err(ref e) if e.kind() == io::ErrorKind::NotFound => {
                debug!("{} does not exist on the server", file.display());
                future::ok(StatusCode::NOT_FOUND)
            }
            Err(e) => future::err(e.into()),
        })
    })
}
