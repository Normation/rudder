// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use std::{
    io::ErrorKind,
    path::Path,
    time::{Duration, SystemTime},
};

use anyhow::Error;
use futures::{future, StreamExt};
use inotify::{Inotify, WatchMask};
use tokio::{
    fs::{read_dir, remove_file, ReadDir},
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

        let files = match read_dir(path.clone()).await {
            Ok(f) => f,
            Err(e) => {
                error!("list file: {}", e);
                continue;
            }
        };
        remove_old_files(files, cfg.retention).await;
    }
}

/// Removes the listed files that have not been touched for `retention`.
///
/// Gives up the pass on a listing error rather than trying the next entry: the directory is
/// listed again at the next tick anyway, while retrying here spins at full speed for as long
/// as the error lasts.
async fn remove_old_files(mut files: ReadDir, retention: Duration) {
    let sys_time = SystemTime::now();

    loop {
        let entry = match files.next_entry().await {
            Ok(Some(e)) => e,
            // Nothing to do
            Ok(None) => break,
            Err(e) => {
                error!("entry error, stopping this cleanup: {}", e);
                break;
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

        if since > retention {
            let path = entry.path();
            debug!("removing old file: {:?}", path);
            remove_file(path)
                .await
                .unwrap_or_else(|e| error!("removal error: {}", e));
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

        let files = match read_dir(path.clone()).await {
            Ok(f) => f,
            Err(e) => {
                error!("list file: {}", e);
                continue;
            }
        };
        send_new_files(files, cfg.limit, &tx).await?;
    }
}

/// Sends the files that have been waiting for a while, up to `limit` of them.
///
/// An error on a given entry is logged and skipped, as this listing is the only mechanism
/// catching up on the files inotify missed: it has to survive a transient error. Processed
/// files are deleted concurrently, so entries are expected to be gone by the time we look
/// at them.
async fn send_new_files(
    mut files: ReadDir,
    limit: u64,
    tx: &mpsc::Sender<ReceivedFile>,
) -> Result<(), Error> {
    let sys_time = SystemTime::now();

    // Max number of files to handle at each tick
    for _ in 0..limit {
        let entry = match files.next_entry().await {
            Ok(Some(e)) => e,
            // Nothing left to list
            Ok(None) => break,
            Err(e) => {
                error!("entry error, skipping it: {}", e);
                continue;
            }
        };

        let metadata = match entry.metadata().await {
            Ok(m) => m,
            // Already processed and removed since it was listed, nothing to do
            Err(e) if e.kind() == ErrorKind::NotFound => {
                debug!("skipping {:?}: {}", entry.path(), e);
                continue;
            }
            Err(e) => {
                error!("metadata error: {}", e);
                continue;
            }
        };

        let since = sys_time
            .duration_since(metadata.modified().unwrap_or(sys_time))
            // An error indicates a file in the future, let's approximate it to now
            .unwrap_or_else(|_| Duration::new(0, 0));

        if since > Duration::from_secs(30) {
            let path = entry.path();
            debug!("list: {:?}", path);
            tx.send(path).await?;
        }
    }
    Ok(())
}

fn watch_stream<P: AsRef<Path>>(path: P) -> inotify::EventStream<Vec<u8>> {
    // https://github.com/linkerd/linkerd2-proxy/blob/c54377fe097208071a88d7b27501faa54ca212b0/lib/fs-watch/src/lib.rs#L189
    let inotify = Inotify::init().expect("Could not initialize inotify");
    // Event sequence on RHEL7:
    //
    // incoming/ CREATE .davfs.tmp199da1
    // incoming/ OPEN .davfs.tmp199da1
    // incoming/ MODIFY .davfs.tmp199da1
    // incoming/ CLOSE_WRITE,CLOSE .davfs.tmp199da1
    // incoming/ MOVED_FROM .davfs.tmp199da1
    // incoming/ MOVED_TO 2019-08-07T13:05:46+00:00@root.log.gz
    inotify
        .watches()
        .add(path.as_ref(), WatchMask::CLOSE_WRITE | WatchMask::MOVED_TO)
        .expect("Could not watch with inotify");
    inotify
        .into_event_stream(Vec::from(&[0; 2048][..]))
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
        fs::{remove_file as remove_file_sync, rename, File},
        path::PathBuf,
        str::FromStr,
    };

    use filetime::{set_file_mtime, FileTime};
    use tempfile::tempdir;

    use super::*;

    #[tokio::test]
    async fn it_removes_files_older_than_the_retention() {
        let dir = tempdir().unwrap();

        let old = dir.path().join("2021-06-24T10:10:51+00:00@root.log");
        let recent = dir.path().join("2021-06-24T10:10:52+00:00@root.log");
        let vanished = dir.path().join("2021-06-24T10:10:53+00:00@root.log");
        for file in [&old, &recent, &vanished] {
            File::create(file).unwrap();
        }
        for file in [&old, &vanished] {
            set_file_mtime(file, FileTime::from_unix_time(1_580_941_341, 0)).unwrap();
        }

        // An entry whose file is gone by the time we look at it must not stop the pass
        let files = read_dir(dir.path()).await.unwrap();
        remove_file_sync(&vanished).unwrap();

        remove_old_files(files, Duration::from_secs(30)).await;

        assert!(!old.exists());
        assert!(recent.exists());
    }

    /// Files are removed as soon as they are processed, so an entry can be gone by the time
    /// its metadata is read. This used to end the listing task for good, silently, leaving
    /// the files inotify missed unprocessed until the retention sweep deleted them.
    #[tokio::test]
    async fn it_keeps_listing_after_a_file_disappeared() {
        let dir = tempdir().unwrap();

        let waiting = dir.path().join("2021-06-24T10:10:51+00:00@root.log");
        let processed = dir.path().join("2021-06-24T10:10:52+00:00@root.log");
        for file in [&waiting, &processed] {
            File::create(file).unwrap();
            // Only files left untouched for 30s are picked up
            set_file_mtime(file, FileTime::from_unix_time(1_580_941_341, 0)).unwrap();
        }

        // The entries are read when the directory is opened, so removing a file now leaves a
        // listed entry without a file behind it, as a concurrent removal does
        let files = read_dir(dir.path()).await.unwrap();
        remove_file_sync(&processed).unwrap();

        let (tx, mut rx) = mpsc::channel(10);
        send_new_files(files, 50, &tx).await.unwrap();

        // The remaining file is still sent for processing, whatever the listing order
        assert_eq!(rx.recv().await.unwrap(), waiting);
        assert!(rx.try_recv().is_err());
    }

    #[tokio::test]
    async fn it_only_lists_files_waiting_for_a_while() {
        let dir = tempdir().unwrap();

        let waiting = dir.path().join("2021-06-24T10:10:51+00:00@root.log");
        File::create(&waiting).unwrap();
        set_file_mtime(&waiting, FileTime::from_unix_time(1_580_941_341, 0)).unwrap();

        // Just written, may still be incomplete
        File::create(dir.path().join("2021-06-24T10:10:52+00:00@root.log")).unwrap();

        let files = read_dir(dir.path()).await.unwrap();
        let (tx, mut rx) = mpsc::channel(10);
        send_new_files(files, 50, &tx).await.unwrap();

        assert_eq!(rx.recv().await.unwrap(), waiting);
        assert!(rx.try_recv().is_err());
    }

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
