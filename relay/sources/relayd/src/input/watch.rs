// SPDX-License-Identifier: GPL-3.0-or-later WITH GPL-3.0-linking-source-exception
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use std::{
    path::Path,
    time::{Duration, SystemTime},
};

use anyhow::Error;
use futures::{future, StreamExt};
use inotify::{Inotify, WatchMask};
use tokio::{
    fs::{read_dir, remove_file},
    sync::mpsc,
    time::interval,
};
use tracing::{debug, error, info, instrument};

use crate::{
    configuration::main::{CatchupConfig, CleanupConfig, WatchedDirectory},
    processing::ReceivedFile,
};

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
        loop {
            let entry = match files.next_entry().await {
                Ok(Some(e)) => e,
                // Nothing to do
                Ok(None) => break,
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

#[instrument(name = "watcher", level = "debug", skip(tx))]
pub fn watch(path: WatchedDirectory, cfg: CatchupConfig, tx: mpsc::Sender<ReceivedFile>) {
    info!("Starting file watcher on {:#?}", &path);
    tokio::spawn(list_files(path.clone(), cfg, tx.clone()));
    tokio::spawn(watch_files(path, tx));
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

        // Max number of files to handle at each tick
        let mut limit = cfg.limit;
        while limit > 0 {
            limit -= 1;
            if let Some(entry) = files.next_entry().await? {
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
            } else {
                break;
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
        .expect("Could not create inotify event stream")
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
    use std::{
        fs::{rename, File},
        path::PathBuf,
        str::FromStr,
    };

    use tempfile::tempdir;

    use super::*;

    #[tokio::test]
    async fn it_watches_files() {
        let dir = tempdir().unwrap();

        // Mimic real webdav behavior
        let mut watch = watch_stream(dir.path());
        File::create(dir.path().join(".davfs.tmp2760b1")).unwrap();
        let event = watch.next().await.unwrap().unwrap();
        assert_eq!(
            event.name.map(PathBuf::from).unwrap(),
            PathBuf::from_str(".davfs.tmp2760b1").unwrap()
        );
        rename(
            dir.path().join(".davfs.tmp2760b1"),
            dir.path().join("2021-06-24T10:10:51+00:00@root.log.gz"),
        )
        .unwrap();
        let event = watch.next().await.unwrap().unwrap();
        assert_eq!(
            event.name.map(PathBuf::from).unwrap(),
            PathBuf::from_str("2021-06-24T10:10:51+00:00@root.log.gz").unwrap()
        );

        File::create(dir.path().join(".davfs.tmp27ede1")).unwrap();
        let event = watch.next().await.unwrap().unwrap();
        assert_eq!(
            event.name.map(PathBuf::from).unwrap(),
            PathBuf::from_str(".davfs.tmp27ede1").unwrap()
        );
        rename(
            dir.path().join(".davfs.tmp27ede1"),
            dir.path().join("2022-01-24T15:55:01+00:00@root.log"),
        )
        .unwrap();
        let event = watch.next().await.unwrap().unwrap();
        assert_eq!(
            event.name.map(PathBuf::from).unwrap(),
            PathBuf::from_str("2022-01-24T15:55:01+00:00@root.log").unwrap()
        );
    }
}
