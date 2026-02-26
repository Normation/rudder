// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use std::{
    io::{BufRead, BufReader, Read},
    str,
    str::FromStr,
    sync::Arc,
    time::Duration,
};

use anyhow::Error;
use bytes::{Buf, Bytes};
use chrono::Utc;
use humantime::parse_duration;
use percent_encoding::percent_decode_str;
use serde::{Deserialize, Serialize};
use tokio::fs;
use tracing::{debug, error, instrument, warn};
use warp::{
    body,
    filters::{method, BoxedFilter},
    http::StatusCode,
    path, query, Filter, Reply,
};

use crate::{
    data::shared_file::{Metadata, SharedFile},
    error::RudderError,
    JobConfig,
};

pub fn routes_1(job_config: Arc<JobConfig>) -> BoxedFilter<(impl Reply,)> {
    let base = path!("shared-files" / String / String / String);

    let job_config_head = job_config.clone();
    let head = method::head()
        .map(move || job_config_head.clone())
        .and(base)
        .and(query::<SharedFilesHeadParams>())
        .and_then(move |j, target_id, source_id, file_id, params| {
            handlers::head(target_id, source_id, file_id, params, j)
        });

    let job_config_put = job_config;
    let max_size = job_config_put.cfg.shared_files.max_size;
    let put = method::put()
        .map(move || job_config_put.clone())
        // Checking the header is enough as hyper will not read more.
        .and(body::content_length_limit(max_size.as_u64()))
        .and(base)
        .and(query::<SharedFilesPutParams>())
        .and(body::bytes())
        .and_then(move |j, target_id, source_id, file_id, params, buf| {
            handlers::put(target_id, source_id, file_id, params, buf, j)
        });

    head.or(put).boxed()
}

pub mod handlers {
    use warp::{reply, Rejection, Reply};

    use super::*;
    use crate::JobConfig;

    pub async fn put(
        target_id: String,
        source_id: String,
        file_id: String,
        params: SharedFilesPutParams,
        buf: Bytes,
        job_config: Arc<JobConfig>,
    ) -> Result<impl Reply, Rejection> {
        let (source_id, target_id, file_id) = (
            percent_decode_str(&source_id)
                .decode_utf8_lossy()
                .to_string(),
            percent_decode_str(&target_id)
                .decode_utf8_lossy()
                .to_string(),
            percent_decode_str(&file_id).decode_utf8_lossy().to_string(),
        );
        Ok(reply::with_status(
            "".to_string(),
            match super::put(
                target_id,
                source_id,
                file_id,
                params,
                job_config.clone(),
                buf,
            )
            .await
            {
                Ok(x) => x,
                Err(e) => {
                    error!("error while processing request: {}", e);
                    StatusCode::INTERNAL_SERVER_ERROR
                }
            },
        ))
    }

    pub async fn head(
        target_id: String,
        source_id: String,
        file_id: String,
        params: SharedFilesHeadParams,
        job_config: Arc<JobConfig>,
    ) -> Result<impl Reply, Rejection> {
        let (source_id, target_id, file_id) = (
            percent_decode_str(&source_id)
                .decode_utf8_lossy()
                .to_string(),
            percent_decode_str(&target_id)
                .decode_utf8_lossy()
                .to_string(),
            percent_decode_str(&file_id).decode_utf8_lossy().to_string(),
        );
        Ok(reply::with_status(
            "".to_string(),
            match super::head(target_id, source_id, file_id, params, job_config.clone()).await {
                Ok(x) => x,
                Err(e) => {
                    error!("error while processing request: {}", e);
                    StatusCode::INTERNAL_SERVER_ERROR
                }
            },
        ))
    }
}

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

#[instrument(name = "shared_files_put", level = "debug", skip(job_config, body))]
pub async fn put(
    target_id: String,
    source_id: String,
    file_id: String,
    params: SharedFilesPutParams,
    job_config: Arc<JobConfig>,
    body: Bytes,
) -> Result<StatusCode, Error> {
    let file = SharedFile::new(source_id, target_id, file_id)?;

    if job_config.nodes.read().await.is_subnode(&file.target_id) {
        put_local(file, params, job_config, body).await
    } else if job_config.nodes.read().await.i_am_root_server() {
        Err(RudderError::UnknownNode(file.target_id).into())
    } else {
        put_forward(file, params, job_config, body).await
    }
}

async fn put_forward(
    file: SharedFile,
    params: SharedFilesPutParams,
    job_config: Arc<JobConfig>,
    body: Bytes,
) -> Result<StatusCode, Error> {
    let client = job_config.upstream_client.read().await.inner().clone();
    client
        .put(format!(
            "{}/{}/{}",
            job_config.cfg.upstream_url(),
            "relay-api/shared-files",
            file.url(),
        ))
        .query(&params)
        .body(body)
        .send()
        .await
        .map(|r| r.status())
        .map_err(|e| e.into())
}

pub async fn put_local(
    file: SharedFile,
    params: SharedFilesPutParams,
    job_config: Arc<JobConfig>,
    body: Bytes,
) -> Result<StatusCode, Error> {
    if !job_config.nodes.read().await.is_subnode(&file.source_id) {
        warn!("unknown source {}", file.source_id);
        return Ok(StatusCode::NOT_FOUND);
    }

    let mut stream = BufReader::new(body.reader());
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
        .join("files")
        .join(&file.source_id);
    let pubkey = meta.pubkey()?;

    let known_key_hash = job_config.nodes.read().await.key_hash(&file.source_id)?;
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
    fs::create_dir_all(&base_path).await?;
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
    )
    .await?;
    fs::write(&base_path.join(file.file_id), file_content).await?;
    Ok(StatusCode::OK)
}

#[derive(Deserialize, Serialize, Debug)]
pub struct SharedFilesHeadParams {
    hash: String,
}

#[instrument(name = "shared_files_head", level = "debug", skip(job_config))]
pub async fn head(
    target_id: String,
    source_id: String,
    file_id: String,
    params: SharedFilesHeadParams,
    job_config: Arc<JobConfig>,
) -> Result<StatusCode, Error> {
    let file = SharedFile::new(source_id, target_id, file_id)?;

    if job_config.nodes.read().await.is_subnode(&file.target_id) {
        head_local(file, params, job_config).await
    } else if job_config.nodes.read().await.i_am_root_server() {
        Err(RudderError::UnknownNode(file.target_id).into())
    } else {
        head_forward(file, params, job_config).await
    }
}

async fn head_forward(
    file: SharedFile,
    params: SharedFilesHeadParams,
    job_config: Arc<JobConfig>,
) -> Result<StatusCode, Error> {
    let client = job_config.upstream_client.read().await.inner().clone();

    client
        .head(format!(
            "{}/{}/{}",
            job_config.cfg.upstream_url(),
            "relay-api/shared-files",
            file.url(),
        ))
        .query(&params)
        .send()
        .await
        .map(|r| r.status())
        .map_err(|e| e.into())
}

pub async fn head_local(
    file: SharedFile,
    params: SharedFilesHeadParams,
    job_config: Arc<JobConfig>,
) -> Result<StatusCode, Error> {
    let file_path = job_config
        .cfg
        .shared_files
        .path
        .join(&file.target_id)
        .join("files")
        .join(&file.source_id)
        .join(format!("{}.metadata", file.file_id));

    if !file_path.exists() {
        debug!("file {} does not exist", file_path.display());
        return Ok(StatusCode::NOT_FOUND);
    }

    let metadata = Metadata::from_str(&fs::read_to_string(&file_path).await?)?;
    let metadata_hash = metadata.hash.hex();

    Ok(if metadata_hash == params.hash {
        debug!(
            "file {} has given hash '{}'",
            file_path.display(),
            metadata_hash
        );
        StatusCode::OK
    } else {
        debug!(
            "file {} has '{}' hash but given hash is '{}'",
            file_path.display(),
            metadata_hash,
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
