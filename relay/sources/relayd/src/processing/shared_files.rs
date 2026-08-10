// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use std::{
    ffi::OsStr,
    path::Path,
    str::{self, FromStr},
    sync::Arc,
    time::{SystemTime, UNIX_EPOCH},
};

use anyhow::{Context, Error, anyhow};
use tokio::{
    fs::{read, remove_file},
    time::interval,
};
use tracing::{Level, debug, error, info, span};
use walkdir::WalkDir;

use crate::{
    JobConfig,
    configuration::main::{SharedFilesCleanupConfig, WatchedDirectory},
    data::shared_file::Metadata,
};

pub fn start(job_config: &Arc<JobConfig>) {
    let span = span!(Level::TRACE, "shared_files");
    let _enter = span.enter();

    let root_path = job_config.cfg.shared_files.path.clone();

    tokio::spawn(cleanup(root_path, job_config.cfg.shared_files.cleanup));
}

async fn expired(file: &Path) -> Result<bool, Error> {
    let raw = read(file)
        .await
        .with_context(|| format!("opening {}", file.display()))?;
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
    debug!("starting shared-files cleanup in {:?}", path);

    loop {
        timer.tick().await;
        cleanup_pass(&path).await;
    }
}

/// Single cleanup pass over the shared-files directory, removing expired files.
async fn cleanup_pass(path: &Path) {
    debug!("cleaning shared-files in {:?}", path);

    for entry in WalkDir::new(path).into_iter().filter_map(|e| e.ok()) {
        // If metadata file exists assume file is here
        let metadata_file = entry.path();
        if metadata_file.extension().and_then(OsStr::to_str) != Some("metadata") {
            continue;
        }
        debug!("considering shared-files {:?}", metadata_file);

        // Get file name by removing the `.metadata` extension. File ids can contain
        // dots (`application.properties`), so only use the metadata path as walked,
        // never rebuild it from the stem.
        let shared_file = metadata_file
            .parent()
            .unwrap()
            .join(metadata_file.file_stem().unwrap());

        match expired(metadata_file).await {
            Ok(true) => {
                info!("removing expired shared-file: {:?}", shared_file);
                remove_file(&shared_file)
                    .await
                    .unwrap_or_else(|e| error!("removal error: {}", e));
                remove_file(metadata_file)
                    .await
                    .unwrap_or_else(|e| error!("removal error: {}", e));
            }
            Ok(false) => (),
            Err(e) => error!("shared-file expiration check error: {}", e),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs::{read_dir, read_to_string, write};

    // Absolute, as the working directory of the test process depends on the runner
    const FIXTURES: &str = concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/tests/api_shared_files/37817c4d-fbf7-4850-a985-50021f4e8f41/files/e745a140-40bc-4b86-b6dc-084488fc906b"
    );

    // Deliberately outside of `tests/api_shared_files`: that directory is the shared-files
    // root of the integration tests, and the cleanup job runs a first pass as soon as
    // relayd starts, so any expired file stored there gets deleted from the source tree.
    const EXPIRED_METADATA: &str =
        concat!(env!("CARGO_MANIFEST_DIR"), "/tests/files/expired.metadata");

    #[tokio::test]
    async fn it_removes_expired_files_with_dots_in_their_id() {
        let dir = tempfile::tempdir().unwrap();

        // Derived from a real metadata file, as the public key it holds gets parsed
        let template = read_to_string(format!("{FIXTURES}/file.metadata")).unwrap();
        let with_expiration = |expires: i64| {
            template
                .lines()
                .map(|l| {
                    if l.starts_with("expires=") {
                        format!("expires={expires}")
                    } else {
                        l.to_string()
                    }
                })
                .collect::<Vec<_>>()
                .join("\n")
        };

        // The canonical example of a file id containing dots
        write(dir.path().join("application.properties"), "content").unwrap();
        write(
            dir.path().join("application.properties.metadata"),
            with_expiration(1_580_941_341),
        )
        .unwrap();

        // Not expired, must be left untouched
        write(dir.path().join("other.properties"), "content").unwrap();
        write(
            dir.path().join("other.properties.metadata"),
            with_expiration(2_061_475_500),
        )
        .unwrap();

        cleanup_pass(dir.path()).await;

        let mut left: Vec<String> = read_dir(dir.path())
            .unwrap()
            .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
            .collect();
        left.sort();

        // Both the content and the metadata of the expired file must be gone
        assert_eq!(left, vec!["other.properties", "other.properties.metadata"]);
    }

    #[tokio::test]
    async fn it_reads_expire_metadata() {
        assert!(expired(Path::new(EXPIRED_METADATA)).await.unwrap());
        assert!(
            !expired(Path::new(&format!("{FIXTURES}/file.metadata")))
                .await
                .unwrap()
        );
    }
}
