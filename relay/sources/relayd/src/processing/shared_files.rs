// SPDX-License-Identifier: GPL-3.0-or-later WITH GPL-3.0-linking-source-exception
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use std::ffi::OsStr;
use std::{
    path::Path,
    str::{self, FromStr},
    sync::Arc,
    time::{SystemTime, UNIX_EPOCH},
};

use anyhow::{anyhow, Error};
use tokio::{
    fs::{read, remove_file},
    time::interval,
};
use tracing::{debug, error, info, span, Level};
use walkdir::WalkDir;

use crate::{
    configuration::main::{SharedFilesCleanupConfig, WatchedDirectory},
    data::shared_file::Metadata,
    JobConfig,
};

pub fn start(job_config: &Arc<JobConfig>) {
    let span = span!(Level::TRACE, "shared_files");
    let _enter = span.enter();

    let root_path = job_config.cfg.shared_files.path.clone();

    tokio::spawn(cleanup(root_path, job_config.cfg.shared_files.cleanup));
}

async fn expired(file: &Path) -> Result<bool, Error> {
    let raw = read(file.with_extension("metadata")).await?;
    let metadata = str::from_utf8(&raw)?;

    let parsed = Metadata::from_str(metadata)?;
    let expiration = parsed
        .expires
        .ok_or_else(|| anyhow!("Missing expires field in {:?}", file))?;

    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("Time went backwards")
        .as_secs();

    Ok(expiration < now as i64)
}

// special cleanup implementation as retention is based on shared files metadata content
pub async fn cleanup(path: WatchedDirectory, cfg: SharedFilesCleanupConfig) -> Result<(), Error> {
    let mut timer = interval(cfg.frequency);

    loop {
        timer.tick().await;
        debug!("cleaning shared-files in {:?}", path);

        for entry in WalkDir::new(&path).into_iter().filter_map(|e| e.ok()) {
            let file = entry.path();
            // If metadata file exists assume file is here
            if file.extension().and_then(OsStr::to_str) == Some("metadata") {
                // Get file name by removing extension
                let shared_file = path.parent().unwrap().join(path.file_stem().unwrap());
                if expired(&shared_file).await? {
                    info!("removing expired shared-file: {:?}", shared_file);
                    remove_file(&shared_file)
                        .await
                        .unwrap_or_else(|e| error!("removal error: {}", e));
                    remove_file(&shared_file.with_extension("metadata"))
                        .await
                        .unwrap_or_else(|e| error!("removal error: {}", e));
                    remove_file(&shared_file.with_extension("sign"))
                        .await
                        .unwrap_or_else(|e| error!("removal error: {}", e));
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn it_reads_expire_metadata() {
        assert!(expired(Path::new("tests/api_shared_files/37817c4d-fbf7-4850-a985-50021f4e8f41/files/e745a140-40bc-4b86-b6dc-084488fc906b/file.metadata")).await.unwrap());
        assert!(!expired(Path::new("tests/api_shared_files/37817c4d-fbf7-4850-a985-50021f4e8f41/files/e745a140-40bc-4b86-b6dc-084488fc906b/file-future.metadata")).await.unwrap());
    }
}
