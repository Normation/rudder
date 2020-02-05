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

use crate::{
    data::shared_file::{Metadata, SharedFile},
    error::Error,
    JobConfig,
};
use bytes::IntoBuf;
use chrono::Utc;
use futures::future::Future;
use hex;
use humantime::parse_duration;
use serde::{Deserialize, Serialize};
use std::{
    fs,
    io::{BufRead, BufReader, Read},
    str,
    str::FromStr,
    sync::Arc,
    time::Duration,
};
use tracing::{debug, span, warn, Level};
use warp::{body::FullBody, http::StatusCode, Buf};

#[derive(Deserialize, Serialize, Debug)]
pub struct SharedFilesPutParams {
    ttl: String,
}

impl SharedFilesPutParams {
    #[cfg(test)]
    fn new(ttl: &str) -> Self {
        Self {
            ttl: ttl.to_string(),
        }
    }

    fn ttl(&self) -> Result<Duration, Error> {
        match self.ttl.parse::<u64>() {
            // No units -> seconds
            Ok(s) => Ok(Duration::from_secs(s)),
            // Else parse as human time
            Err(_) => parse_duration(&self.ttl).map_err(|e| e.into()),
        }
    }
}

pub fn put(
    target_id: String,
    source_id: String,
    file_id: String,
    params: SharedFilesPutParams,
    job_config: Arc<JobConfig>,
    body: FullBody,
) -> Result<StatusCode, Error> {
    let span = span!(
        Level::INFO,
        "shared_files_put",
        target_id = %target_id,
        source_id = %source_id,
        file_id = %file_id,
    );
    let _enter = span.enter();

    let file = SharedFile::new(source_id, target_id, file_id)?;

    if job_config
        .nodes
        .read()
        .expect("Cannot read nodes list")
        .is_subnode(&file.target_id)
    {
        put_local(file, params, job_config, body)
    } else if job_config.cfg.general.node_id == "root" {
        Err(Error::UnknownNode(file.target_id))
    } else {
        put_forward(file, params, job_config, body)
    }
}

fn put_forward(
    file: SharedFile,
    params: SharedFilesPutParams,
    job_config: Arc<JobConfig>,
    body: FullBody,
) -> Result<StatusCode, Error> {
    job_config
        .client
        .clone()
        .put(&format!(
            "{}/{}/{}",
            job_config.cfg.output.upstream.url,
            "relay-api/shared-files",
            file.url(),
        ))
        .query(&params)
        .body(body.into_buf().collect::<Vec<u8>>())
        .send()
        .wait()
        .map(|r| r.status())
        .map_err(|e| e.into())
}

pub fn put_local(
    file: SharedFile,
    params: SharedFilesPutParams,
    job_config: Arc<JobConfig>,
    body: FullBody,
) -> Result<StatusCode, Error> {
    if !job_config
        .nodes
        .read()
        .expect("Cannot read nodes list")
        .is_subnode(&file.source_id)
    {
        warn!("unknown source {}", file.source_id);
        return Ok(StatusCode::NOT_FOUND);
    }

    let mut stream = BufReader::new(body.into_buf().reader());
    let mut raw_meta = String::new();
    // Here we cannot iterate on lines as the file content may not be valid UTF-8.
    let mut read = 2;
    // Let's read while we find an empty line.
    while read > 1 {
        read = stream.read_line(&mut raw_meta)?;
    }
    let meta = Metadata::from_str(&raw_meta)?;

    let base_path = job_config
        .cfg
        .shared_files
        .path
        .join(&file.target_id)
        .join(&file.source_id);
    let pubkey = meta.pubkey()?;

    let known_key_hash = job_config
        .nodes
        .read()
        .expect("Cannot read nodes list")
        .key_hash(&file.source_id)
        .ok_or_else(|| Error::UnknownNode(file.source_id.to_string()))?;
    let key_hash = known_key_hash.hash_type.hash(&pubkey.public_key_to_der()?);
    if key_hash != known_key_hash {
        warn!(
            "hash of public key ({}) does not match known hash ({})",
            key_hash, known_key_hash
        );
        return Ok(StatusCode::NOT_FOUND);
    }

    // Read file content
    let mut file_content = vec![];
    stream.read_to_end(&mut file_content)?;

    match meta.validate_signature(
        &file_content,
        meta.hash.hash_type,
        &hex::decode(&meta.digest)?,
    ) {
        Ok(is_valid) => {
            if !is_valid {
                warn!("invalid signature");
                return Ok(StatusCode::INTERNAL_SERVER_ERROR);
            }
        }
        Err(e) => {
            warn!("error checking file signature: {}", e);
            return Ok(StatusCode::INTERNAL_SERVER_ERROR);
        }
    }

    // Everything is correct, let's store the file
    fs::create_dir_all(&base_path)?;
    fs::write(
        &base_path.join(format!("{}.metadata", file.file_id)),
        format!(
            "{}expires={}\n",
            meta,
            match params.ttl() {
                // Removal timestamp = now + ttl
                Ok(ttl) => (Utc::now()
                    + chrono::Duration::from_std(ttl).expect("Unexpectedly large duration"))
                .timestamp(),
                Err(e) => {
                    warn!("invalid ttl: {}", e);
                    return Ok(StatusCode::INTERNAL_SERVER_ERROR);
                }
            }
        ),
    )?;
    fs::write(&base_path.join(file.file_id), file_content)?;
    Ok(StatusCode::OK)
}

#[derive(Deserialize, Serialize, Debug)]
pub struct SharedFilesHeadParams {
    hash: String,
}

pub fn head(
    target_id: String,
    source_id: String,
    file_id: String,
    params: SharedFilesHeadParams,
    job_config: Arc<JobConfig>,
) -> Result<StatusCode, Error> {
    let span = span!(
        Level::INFO,
        "shared_files_head",
        target_id = %target_id,
        source_id = %source_id,
        file_id = %file_id,
    );
    let _enter = span.enter();

    let file = SharedFile::new(source_id, target_id, file_id)?;

    if job_config
        .nodes
        .read()
        .expect("Cannot read nodes list")
        .is_subnode(&file.target_id)
    {
        head_local(file, params, job_config)
    } else if job_config.cfg.general.node_id == "root" {
        Err(Error::UnknownNode(file.target_id))
    } else {
        head_forward(file, params, job_config)
    }
}

fn head_forward(
    file: SharedFile,
    params: SharedFilesHeadParams,
    job_config: Arc<JobConfig>,
) -> Result<StatusCode, Error> {
    job_config
        .client
        .clone()
        .head(&format!(
            "{}/{}/{}",
            job_config.cfg.output.upstream.url,
            "relay-api/shared-files",
            file.url(),
        ))
        .query(&params)
        .send()
        .wait()
        .map(|r| r.status())
        .map_err(|e| e.into())
}

pub fn head_local(
    file: SharedFile,
    params: SharedFilesHeadParams,
    job_config: Arc<JobConfig>,
) -> Result<StatusCode, Error> {
    let file_path = job_config
        .cfg
        .shared_files
        .path
        .join(&file.target_id)
        .join(&file.source_id)
        .join(format!("{}.metadata", file.file_id));

    if !file_path.exists() {
        debug!("file {} does not exist", file_path.display());
        return Ok(StatusCode::NOT_FOUND);
    }

    let metadata = Metadata::from_str(&fs::read_to_string(&file_path)?)?;

    Ok(if metadata.hash.value == params.hash {
        debug!(
            "file {} has given hash '{}'",
            file_path.display(),
            metadata.hash.value
        );
        StatusCode::OK
    } else {
        debug!(
            "file {} has '{}' hash but given hash is '{}'",
            file_path.display(),
            metadata.hash.value,
            params.hash,
        );
        StatusCode::NOT_FOUND
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    pub fn it_parses_ttl() {
        assert_eq!(
            SharedFilesPutParams::new("1h 7s").ttl().unwrap(),
            Duration::from_secs(3600 + 7)
        );
        assert_eq!(
            SharedFilesPutParams::new("1hour 2minutes").ttl().unwrap(),
            Duration::from_secs(3600 + 120)
        );
        assert_eq!(
            SharedFilesPutParams::new("1d 7seconds").ttl().unwrap(),
            Duration::from_secs(86400 + 7)
        );
        assert_eq!(
            SharedFilesPutParams::new("9136").ttl().unwrap(),
            Duration::from_secs(9136)
        );
        assert!(SharedFilesPutParams::new("913j").ttl().is_err());
        assert!(SharedFilesPutParams::new("913b83").ttl().is_err());
        assert!(SharedFilesPutParams::new("913h 89j").ttl().is_err());
    }
}
