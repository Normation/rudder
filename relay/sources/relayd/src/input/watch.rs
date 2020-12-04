// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use crate::{
    configuration::main::{CatchupConfig, CleanupConfig, WatchedDirectory},
    processing::ReceivedFile,
    JobConfig,
};
use anyhow::Error;
use futures::{future, StreamExt};
use inotify::{Inotify, WatchMask};
use std::{
    path::Path,
    sync::Arc,
    time::{Duration, SystemTime},
};
use tokio::{
    fs::{read_dir, remove_file},
    sync::mpsc,
    time::interval,
};
use tracing::{debug, error, info, span, Level};

pub async fn cleanup(path: WatchedDirectory, cfg: CleanupConfig) -> Result<(), Error> {
    let mut timer = interval(cfg.frequency);

    loop {
        timer.tick().await;

        debug!("cleaning {:?}", path);
        let sys_time = SystemTime::now();

        let mut files = match read_dir(path.clone()).await {
            Ok(f) => f,
            Err(e) => {
                error!("list file: {}", e);
                continue;
            }
        };
        while let Some(entry) = files.next().await {
            let entry = match entry {
                Ok(e) => e,
                Err(e) => {
                    error!("entry error: {}", e);
                    continue;
                }
            };
            let metadata = match entry.metadata().await {
                Ok(m) => m,
                Err(e) => {
                    error!("metadata error: {}", e);
                    continue;
                }
            };

            let since = sys_time
                .duration_since(metadata.modified().unwrap_or(sys_time))
                // An error indicates a file in the future, let's approximate it to now
                .unwrap_or_else(|_| Duration::new(0, 0));

            if since > cfg.retention {
                let path = entry.path();
                debug!("removing old file: {:?}", path);
                remove_file(path)
                    .await
                    .unwrap_or_else(|e| error!("removal error: {}", e));
            }
        }
    }
}

pub fn watch(path: &WatchedDirectory, job_config: &Arc<JobConfig>, tx: mpsc::Sender<ReceivedFile>) {
    info!("Starting file watcher on {:#?}", &path);
    let report_span = span!(Level::TRACE, "watcher");
    let _report_enter = report_span.enter();
    tokio::spawn(list_files(
        path.clone(),
        job_config.cfg.processing.reporting.catchup,
        tx.clone(),
    ));
    tokio::spawn(watch_files(path.clone(), tx));
}

async fn list_files(
    path: WatchedDirectory,
    cfg: CatchupConfig,
    tx: mpsc::Sender<ReceivedFile>,
) -> Result<(), Error> {
    let mut timer = interval(cfg.frequency);

    loop {
        timer.tick().await;
        debug!("listing {:?}", path);

        let tx = tx.clone();
        let sys_time = SystemTime::now();

        let mut files = match read_dir(path.clone()).await {
            Ok(f) => f,
            Err(e) => {
                error!("list file: {}", e);
                continue;
            }
        };

        while let Some(entry) = files.next().await {
            let entry = entry?;

            let metadata = entry.metadata().await?;
            let since = sys_time
                .duration_since(metadata.modified().unwrap_or(sys_time))
                // An error indicates a file in the future, let's approximate it to now
                .unwrap_or_else(|_| Duration::new(0, 0));

            if since > Duration::from_secs(30) {
                let path = entry.path();
                debug!("list: {:?}", path);
                tx.clone().send(path).await?;
            }
        }
    }
}

fn watch_stream<P: AsRef<Path>>(path: P) -> inotify::EventStream<Vec<u8>> {
    // https://github.com/linkerd/linkerd2-proxy/blob/c54377fe097208071a88d7b27501faa54ca212b0/lib/fs-watch/src/lib.rs#L189
    let mut inotify = Inotify::init().expect("Could not initialize inotify");
    // Event sequence on RHEL7:
    //
    // incoming/ CREATE .davfs.tmp199da1
    // incoming/ OPEN .davfs.tmp199da1
    // incoming/ MODIFY .davfs.tmp199da1
    // incoming/ CLOSE_WRITE,CLOSE .davfs.tmp199da1
    // incoming/ MOVED_FROM .davfs.tmp199da1
    // incoming/ MOVED_TO 2019-08-07T13:05:46+00:00@root.log.gz
    inotify
        .add_watch(path.as_ref(), WatchMask::CLOSE_WRITE | WatchMask::MOVED_TO)
        .expect("Could not watch with inotify");
    inotify
        .event_stream(Vec::from(&[0; 2048][..]))
        .expect("Could no create inotify event stream")
}

async fn watch_files<P: AsRef<Path>>(path: P, tx: mpsc::Sender<ReceivedFile>) -> Result<(), Error> {
    let path_prefix = path.as_ref().to_path_buf();

    let mut files = watch_stream(&path)
        .map(|entry| entry.unwrap().name)
        // If it is None, it means it is not an event on a file in the directory, skipping
        .filter(|e| future::ready(e.is_some()))
        .map(|entry| entry.expect("inotify entry has no name"));

    while let Some(file) = files.next().await {
        // inotify gives the filename, add the entire path
        let full_path = path_prefix.join(file);
        debug!("inotify: {:?}", full_path);

        tx.clone().send(full_path).await?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{fs::File, path::PathBuf, str::FromStr};
    use tempfile::tempdir;

    #[tokio::test]
    async fn it_watches_files() {
        let dir = tempdir().unwrap();

        let mut watch = watch_stream(dir.path());
        File::create(dir.path().join("2019-01-24T15:55:01+00:00@root.log")).unwrap();
        let event = watch.next().await.unwrap().unwrap();
        assert_eq!(
            event.name.map(PathBuf::from).unwrap(),
            PathBuf::from_str("2019-01-24T15:55:01+00:00@root.log").unwrap()
        );
    }
}
