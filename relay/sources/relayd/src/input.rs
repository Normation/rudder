use crate::{
    configuration::{InventoryOutputSelect, ReportingOutputSelect, WatchedDirectory},
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

pub fn serve(job_config: Arc<JobConfig>, stats: mpsc::Sender<Event>) {
    let (reporting_tx, reporting_rx) = mpsc::channel(1_024);
    tokio::spawn(treat_reports(job_config.clone(), reporting_rx, stats.clone()));
    watch(
        &job_config
            .clone()
            .cfg
            .processing
            .reporting
            .directory
            .join("incoming"),
        &reporting_tx,
    );

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
        };

        tokio::spawn(lazy(|| treat_file));
        Ok(())
    })
}

fn watch(path: &WatchedDirectory, tx: &mpsc::Sender<ReceivedFile>) {
    info!("Starting file watcher on {:#?}", &path; "component" => "watcher");
    // Try to create target dir
    create_dir_all(path).expect("Could not create watched directory");
    tokio::spawn(list_files(path.clone(), tx.clone()));
    tokio::spawn(watch_files(path.clone(), tx.clone()));
}

fn list_files(
    path: WatchedDirectory,
    tx: mpsc::Sender<ReceivedFile>,
) -> impl Future<Item = (), Error = ()> {
    Interval::new(Instant::now(), Duration::from_secs(1))
        .map_err(|e| warn!("interval error: {}", e; "component" => "watcher"))
        .for_each(move |_instant| {
            let tx = tx.clone();
            let sys_time = SystemTime::now();

            // TODO make max read number configurable
            read_dir(path.clone())
                .flatten_stream()
                .take(50)
                .map_err(|_| ())
                .filter(move |entry| {
                    poll_fn(move || entry.poll_metadata())
                        .map(|metadata| metadata.modified().unwrap())
                        .map(|modified| sys_time.duration_since(modified).unwrap())
                        .map(|duration| duration > Duration::from_secs(60))
                        .map_err(|_| false)
                        // TODO async filter (https://github.com/rust-lang-nursery/futures-rs/pull/728)
                        .wait()
                        .unwrap()
                })
                .for_each(move |entry| {
                    let path = entry.path();
                    debug!("list: {:?}", path; "component" => "watcher");
                    tx.clone()
                        .send(path)
                        .map_err(|e| warn!("list errored: {}", e; "component" => "watcher"))
                        .map(|_| ())
                })
        })
}

fn watch_stream(path: WatchedDirectory) -> inotify::EventStream<[u8; 32]> {
    let watch_directory = path;

    // Watch new files
    let mut inotify = Inotify::init().expect("Could not initialize inotify");
    inotify
        .add_watch(
            watch_directory.clone(),
            WatchMask::CREATE | WatchMask::MODIFY,
        )
        .expect("Could not watch with inotify");

    inotify.event_stream([0; 32])
}

fn watch_files(
    path: WatchedDirectory,
    tx: mpsc::Sender<ReceivedFile>,
) -> impl Future<Item = (), Error = ()> {
    watch_stream(path)
        .map_err(|e| {
            warn!("send error: {}", e; "component" => "watcher");
        })
        .for_each(move |entry| {
            let path = entry.name.map(PathBuf::from).unwrap();

            tx.clone()
                .send(path)
                .map_err(|e| warn!("send error: {}", e; "component" => "watcher"))
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
            .unwrap();
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
        create_dir_all("tests/test_watch").unwrap();
        let _ = remove_file("tests/test_watch/foo.log");

        let watch = watch_stream(PathBuf::from_str("tests/test_watch").unwrap());
        File::create("tests/test_watch/foo.log").unwrap();
        let events = watch.take(1).wait().collect::<Vec<_>>();

        assert_eq!(events.len(), 1);

        for event in events {
            if let Ok(event) = event {
                assert_eq!(
                    event.name.map(PathBuf::from).unwrap(),
                    PathBuf::from_str("foo.log").unwrap()
                );
            }
        }
    }
}
