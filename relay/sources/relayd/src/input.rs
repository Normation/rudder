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
    configuration::{
        CatchupConfig, InventoryOutputSelect, ReportingOutputSelect, WatchedDirectory,
    },
    data::reporting::RunLog,
    error::Error,
    output::database::insert_runlog,
    stats::Event,
    JobConfig,
};
use futures::{
    future::{poll_fn, Future},
    lazy,
    sync::mpsc,
    Stream,
};
use inotify::{Inotify, WatchMask};
use slog::{slog_debug, slog_info, slog_warn};
use slog_scope::{debug, info, warn};
use std::{
    fs::create_dir_all,
    path::PathBuf,
    sync::Arc,
    time::{Duration, Instant, SystemTime},
};
use tokio::{
    fs::{file::File, read_dir},
    prelude::*,
    timer::Interval,
};

pub type ReceivedFile = PathBuf;

pub fn serve_reports(job_config: Arc<JobConfig>, stats: mpsc::Sender<Event>) {
    let (reporting_tx, reporting_rx) = mpsc::channel(1_024);
    tokio::spawn(treat_reports(
        job_config.clone(),
        reporting_rx,
        stats.clone(),
    ));
    watch(
        &job_config
            .clone()
            .cfg
            .processing
            .reporting
            .directory
            .join("incoming"),
        job_config.clone(),
        &reporting_tx,
    );
}

pub fn serve_inventories(job_config: Arc<JobConfig>, stats: mpsc::Sender<Event>) {
    let (inventory_tx, inventory_rx) = mpsc::channel(1_024);
    tokio::spawn(treat_inventories(job_config.clone(), inventory_rx, stats));
    watch(
        &job_config
            .clone()
            .cfg
            .processing
            .inventory
            .directory
            .join("incoming"),
        job_config.clone(),
        &inventory_tx,
    );
    watch(
        &job_config
            .clone()
            .cfg
            .processing
            .inventory
            .directory
            .join("accepted-nodes-updates"),
        job_config.clone(),
        &inventory_tx,
    );
}

fn treat_reports(
    job_config: Arc<JobConfig>,
    rx: mpsc::Receiver<ReceivedFile>,
    stats: mpsc::Sender<Event>,
) -> impl Future<Item = (), Error = ()> {
    rx.for_each(move |file| {
        let stat_event = stats
            .clone()
            .send(Event::ReportReceived)
            .map_err(|e| warn!("receive error: {}", e; "component" => "watcher"))
            .map(|_| ());
        tokio::spawn(lazy(|| stat_event));

        debug!("received: {:?}", file; "component" => "watcher");

        let treat_file = match job_config.cfg.processing.reporting.output {
            ReportingOutputSelect::Database => insert(&file, job_config.clone(), stats.clone()),
            ReportingOutputSelect::Upstream => unimplemented!(),
            ReportingOutputSelect::Disabled => unreachable!(),
        };

        tokio::spawn(lazy(|| treat_file));
        Ok(())
    })
}

fn treat_inventories(
    job_config: Arc<JobConfig>,
    rx: mpsc::Receiver<ReceivedFile>,
    stats: mpsc::Sender<Event>,
) -> impl Future<Item = (), Error = ()> {
    let stat_event = stats
        .clone()
        .send(Event::ReportReceived)
        .map_err(|e| warn!("receive error: {}", e; "component" => "watcher"))
        .map(|_| ());
    tokio::spawn(lazy(|| stat_event));

    rx.for_each(move |file| {
        debug!("received: {:?}", file; "component" => "watcher");
        let treat_file = match job_config.cfg.processing.inventory.output {
            InventoryOutputSelect::Upstream => insert(&file, job_config.clone(), stats.clone()),
            InventoryOutputSelect::Disabled => unreachable!(),
        };

        tokio::spawn(lazy(|| treat_file));
        Ok(())
    })
}

fn watch(path: &WatchedDirectory, job_config: Arc<JobConfig>, tx: &mpsc::Sender<ReceivedFile>) {
    info!("Starting file watcher on {:#?}", &path; "component" => "watcher");
    // Try to create target dir
    create_dir_all(path).expect("Could not create watched directory");
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
        .map_err(|e| warn!("interval error: {}", e; "component" => "watcher"))
        .for_each(move |_instant| {
            debug!("listing {:?}", path; "component" => "watcher");

            let tx = tx.clone();
            let sys_time = SystemTime::now();

            read_dir(path.clone())
                .flatten_stream()
                .take(cfg.limit)
                .map_err(|e| warn!("list error: {}", e; "component" => "watcher"))
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
                        .map_err(|e| warn!("list filter error: {}", e; "component" => "watcher"))
                        // TODO async filter (https://github.com/rust-lang-nursery/futures-rs/pull/728)
                        .wait()
                        .unwrap_or(false)
                })
                .for_each(move |entry| {
                    let path = entry.path();
                    debug!("list: {:?}", path; "component" => "watcher");
                    tx.clone()
                        .send(path)
                        .map_err(|e| warn!("list error: {}", e; "component" => "watcher"))
                        .map(|_| ())
                })
        })
}

fn watch_stream(path: WatchedDirectory) -> inotify::EventStream<Vec<u8>> {
    // https://github.com/linkerd/linkerd2-proxy/blob/c54377fe097208071a88d7b27501faa54ca212b0/lib/fs-watch/src/lib.rs#L189
    let mut inotify = Inotify::init().expect("Could not initialize inotify");
    inotify
        .add_watch(path.clone(), WatchMask::CREATE | WatchMask::MODIFY)
        .expect("Could not watch with inotify");
    inotify.event_stream(Vec::from(&[0; 2048][..]))
}

fn watch_files(
    path: WatchedDirectory,
    tx: mpsc::Sender<ReceivedFile>,
) -> impl Future<Item = (), Error = ()> {
    watch_stream(path)
        .map_err(|e| {
            warn!("watch error: {}", e; "component" => "watcher");
        })
        .map(|entry| entry.name)
        // If it is None, it means it is not an event on a file in the directory, skipping
        .filter(|entry| entry.is_some())
        .map(|entry| entry.expect("inotify entry has no name"))
        .map(PathBuf::from)
        .for_each(move |entry| {
            tx.clone()
                .send(entry)
                .map_err(|e| warn!("watch send error: {}", e; "component" => "watcher"))
                .map(|_| ())
        })
}

fn insert(
    path: &ReceivedFile,
    job_config: Arc<JobConfig>,
    stats: mpsc::Sender<Event>,
) -> impl Future<Item = (), Error = ()> {
    // TODO blocking
    read_file(&path)
        .and_then(|res| res.parse::<RunLog>())
        .map_err(|e| warn!("send error: {}", e; "component" => "parser"))
        .map(move |runlog| {
            insert_runlog(
                &job_config
                    .pool
                    .clone()
                    .expect("output uses database but no config provided"),
                &runlog,
            )
            .map_err(|e| warn!("parse error: {}", e; "component" => "parser"))
        })
        .and_then(|_| {
            stats
                .send(Event::ReportInserted)
                .map_err(|e| warn!("send error: {}", e; "component" => "watcher"))
                .map(|_| ())
        })
}

fn read_file(path: &ReceivedFile) -> impl Future<Item = String, Error = Error> {
    File::open(path.clone())
        .and_then(|file| {
            let buf: Vec<u8> = Vec::new();
            tokio::io::read_to_end(file, buf)
        })
        .map_err(Error::from)
        .and_then(|item| Ok(String::from_utf8(item.1)?))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{
        fs::{create_dir_all, remove_file, File},
        str::FromStr,
    };

    #[test]
    fn it_watches_files() {
        // TODO improve tmp dir handling
        create_dir_all("tests/tmp/test_watch").unwrap();
        // just in case
        let _ = remove_file("tests/tmp/test_watch/2019-01-24T15:55:01+00:00@root.log");

        let watch = watch_stream(PathBuf::from_str("tests/tmp/test_watch").unwrap());
        File::create("tests/tmp/test_watch/2019-01-24T15:55:01+00:00@root.log").unwrap();
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

        // cleanup
        let _ = remove_file("tests/tmp/test_watch/2019-01-24T15:55:01+00:00@root.log");
    }
}
