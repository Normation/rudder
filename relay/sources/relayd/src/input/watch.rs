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
    configuration::main::{CatchupConfig, WatchedDirectory},
    processing::ReceivedFile,
    JobConfig,
};
use futures::{
    future::{poll_fn, Future},
    sync::mpsc,
    Stream,
};
use inotify::{Inotify, WatchMask};
use std::{
    path::Path,
    sync::Arc,
    time::{Duration, Instant, SystemTime},
};
use tokio::{fs::read_dir, prelude::*, timer::Interval};
use tracing::{debug, info, warn};

pub fn watch(
    path: &WatchedDirectory,
    job_config: &Arc<JobConfig>,
    tx: &mpsc::Sender<ReceivedFile>,
) {
    info!("Starting file watcher on {:#?}", &path);
    tokio::spawn(list_files(
        path.clone(),
        job_config.cfg.processing.reporting.catchup,
        tx.clone(),
    ));
    tokio::spawn(watch_files(path.clone(), tx.clone()));
}

fn list_files(
    path: WatchedDirectory,
    cfg: CatchupConfig,
    tx: mpsc::Sender<ReceivedFile>,
) -> impl Future<Item = (), Error = ()> {
    Interval::new(Instant::now(), Duration::from_secs(cfg.frequency))
        .map_err(|e| warn!("interval error: {}", e))
        .for_each(move |_instant| {
            debug!("listing {:?}", path);

            let tx = tx.clone();
            let sys_time = SystemTime::now();

            read_dir(path.clone())
                .flatten_stream()
                .take(cfg.limit)
                .map_err(|e| warn!("list error: {}", e))
                .filter(move |entry| {
                    poll_fn(move || entry.poll_metadata())
                        // If metadata can't be fetched, skip it for now
                        .map(|metadata| metadata.modified().unwrap_or(sys_time))
                        // An error indicates a file in the future, let's approximate it to now
                        .map(|modified| {
                            sys_time
                                .duration_since(modified)
                                .unwrap_or_else(|_| Duration::new(0, 0))
                        })
                        .map(|duration| duration > Duration::from_secs(30))
                        .map_err(|e| warn!("list filter error: {}", e))
                        // TODO async filter (https://github.com/rust-lang-nursery/futures-rs/pull/728)
                        .wait()
                        .unwrap_or(false)
                })
                .for_each(move |entry| {
                    let path = entry.path();
                    debug!("list: {:?}", path);
                    tx.clone()
                        .send(path)
                        .map_err(|e| warn!("list error: {}", e))
                        .map(|_| ())
                })
        })
}

fn watch_stream<P: AsRef<Path>>(path: P) -> inotify::EventStream<Vec<u8>> {
    // https://github.com/linkerd/linkerd2-proxy/blob/c54377fe097208071a88d7b27501faa54ca212b0/lib/fs-watch/src/lib.rs#L189
    let mut inotify = Inotify::init().expect("Could not initialize inotify");
    inotify
        .add_watch(path.as_ref(), WatchMask::CLOSE_WRITE)
        .expect("Could not watch with inotify");
    inotify.event_stream(Vec::from(&[0; 2048][..]))
}

fn watch_files<P: AsRef<Path>>(
    path: P,
    tx: mpsc::Sender<ReceivedFile>,
) -> impl Future<Item = (), Error = ()> {
    let path_prefix = path.as_ref().to_path_buf();
    watch_stream(&path)
        .map_err(|e| {
            warn!("watch error: {}", e);
        })
        .map(|entry| entry.name)
        // If it is None, it means it is not an event on a file in the directory, skipping
        .filter(Option::is_some)
        .map(|entry| entry.expect("inotify entry has no name"))
        // inotify gives the filename, add the entire path
        .map(move |p| {
            let full_path = path_prefix.join(p);
            debug!("inotify: {:?}", path.as_ref());
            full_path
        })
        .for_each(move |entry| {
            tx.clone()
                .send(entry)
                .map_err(|e| warn!("watch send error: {}", e))
                .map(|_| ())
        })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{fs::File, path::PathBuf, str::FromStr};
    use tempfile::tempdir;

    #[test]
    fn it_watches_files() {
        let dir = tempdir().unwrap();

        let watch = watch_stream(dir.path());
        File::create(dir.path().join("2019-01-24T15:55:01+00:00@root.log")).unwrap();
        let events = watch.take(1).wait().collect::<Vec<_>>();

        assert_eq!(events.len(), 1);

        for event in events {
            if let Ok(event) = event {
                assert_eq!(
                    event.name.map(PathBuf::from).unwrap(),
                    PathBuf::from_str("2019-01-24T15:55:01+00:00@root.log").unwrap()
                );
            }
        }
    }
}
