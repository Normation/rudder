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

use crate::hashing::HashType;
use crate::{error::Error, JobConfig};
use serde::Deserialize;
use std::{fs, path::Path, str::FromStr, sync::Arc};
use tracing::debug;
use warp::{http::StatusCode, reply};

// TODO async io
// return text in request?

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
    file: &Path,
    job_config: Arc<JobConfig>,
) -> Result<warp::reply::WithStatus<String>, Error> {
    let file_path = job_config.cfg.shared_folder.path.join(file);
    debug!(
        "Received request for {:#} ({:#} locally) with the following parameters: {:?}",
        file.display(),
        file_path.display(),
        params
    );
    let hash_type = HashType::from_str(&params.hash_type)?;

    Ok(if file_path.exists() {
        if params.hash.is_empty() {
            reply::with_status(
                format!("{} exists but hash is empty", file.display()),
                StatusCode::OK,
            )
        } else {
            let actual_hash = hash_type.hash(&fs::read(file_path)?);
            debug!("{} {} hash is {}", file.display(), hash_type, params.hash);
            if params.hash == actual_hash {
                reply::with_status(
                    format!("{} exists and has same hash", file.display()),
                    StatusCode::NOT_MODIFIED,
                )
            } else {
                reply::with_status(
                    format!(
                        "{:#} exists but its hash is which is different",
                        file.display()
                    ),
                    StatusCode::OK,
                )
            }
        }
    } else {
        reply::with_status(
            format!("{} does not exist on the server", file.display()),
            StatusCode::NOT_FOUND,
        )
    })
}
