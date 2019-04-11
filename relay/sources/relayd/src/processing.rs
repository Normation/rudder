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

use crate::configuration::LogComponent;
use crate::input::read_file_content;
use crate::input::watch::*;
use crate::{
    configuration::{InventoryOutputSelect, ReportingOutputSelect},
    data::RunLog,
    error::Error,
    output::database::{insert_runlog, InsertionBehavior},
    stats::Event,
    JobConfig,
};
use futures::{
    future::{poll_fn, Future},
    lazy,
    sync::mpsc,
    Stream,
};
use std::fs::create_dir_all;
use slog::{slog_debug, slog_error};
use slog_scope::{debug, error};
use std::{path::PathBuf, sync::Arc};
use tokio::fs::remove_file;
use tokio::fs::rename;
use tokio::prelude::*;
use tokio_threadpool::blocking;

pub type ReceivedFile = PathBuf;

pub fn serve_reports(job_config: &Arc<JobConfig>, stats: &mpsc::Sender<Event>) {
    let (reporting_tx, reporting_rx) = mpsc::channel(1_024);
    tokio::spawn(treat_reports(
        job_config.clone(),
        reporting_rx,
        stats.clone(),
    ));
    watch(
        &job_config
            .cfg
            .processing
            .reporting
            .directory
            .join("incoming"),
        &job_config,
        &reporting_tx,
    );
}

pub fn serve_inventories(job_config: &Arc<JobConfig>, stats: &mpsc::Sender<Event>) {
    let (inventory_tx, inventory_rx) = mpsc::channel(1_024);
    tokio::spawn(treat_inventories(
        job_config.clone(),
        inventory_rx,
        stats.clone(),
    ));
    watch(
        &job_config
            .cfg
            .processing
            .inventory
            .directory
            .join("incoming"),
        &job_config,
        &inventory_tx,
    );
    watch(
        &job_config
            .cfg
            .processing
            .inventory
            .directory
            .join("accepted-nodes-updates"),
        &job_config,
        &inventory_tx,
    );
}

fn treat_reports(
    job_config: Arc<JobConfig>,
    rx: mpsc::Receiver<ReceivedFile>,
    stats: mpsc::Sender<Event>,
) -> impl Future<Item = (), Error = ()> {
    rx.for_each(move |file| {
        // TODO parse file name
        let stat_event = stats
            .clone()
            .send(Event::ReportReceived)
            .map_err(|e| error!("receive error: {}", e; "component" => LogComponent::Watcher))
            .map(|_| ());
        // FIXME: no need for a spawn
        tokio::spawn(lazy(|| stat_event));

        debug!("received: {:?}", file; "component" => LogComponent::Watcher);

        let stats_ok = stats.clone();
        let stats_err = stats.clone();
        let file_clone = file.clone();
        let file_move = file.clone();
        let job_config_clone = job_config.clone();
        let treat_file = match job_config.cfg.processing.reporting.output {
            ReportingOutputSelect::Database => output_report_database(
                file_clone,
                job_config.clone(),
            )
            .and_then(|_| {
                stats_ok
                    .send(Event::ReportInserted)
                    .map_err(|e| error!("send error: {}", e; "component" => LogComponent::Parser))
                    .then(|_| {
                        remove_file(file.clone())
                            .map(move |_| debug!("deleted: {:#?}", file))
                            .map_err(
                                |e| error!("error: {}", e; "component" => LogComponent::Parser),
                            )
                    })
            })
            .or_else(|_| {
                stats_err
                    .send(Event::ReportRefused)
                    .map_err(|e| error!("send error: {}", e; "component" => LogComponent::Parser))
                    .then(move |_| {
                        rename(
                            file_move.clone(),
                            {
                                let mut dest = job_config_clone.cfg.processing.reporting.directory.clone();
                                dest.push("failed");
                                // FIXME do at startup
                                create_dir_all(&dest).expect("Could not create failed directory");
                                dest.push(file_move.file_name().expect("not a file"));
                                dest
                            },
                        )
                        .map(move |_| {
                            debug!(
                                "moved: {:#?} to {:#?}",
                                file_move, {
                                let mut dest = job_config_clone.cfg.processing.reporting.directory.clone();
                                dest.push("failed");
                                dest.push(file_move.file_name().expect("not a file"));
                                dest
                            }
                            )
                        })
                        .map_err(|e| error!("error: {}", e; "component" => LogComponent::Parser))
                    })
            }),
            ReportingOutputSelect::Upstream => unimplemented!(),
            // The job should not be started in this case
            ReportingOutputSelect::Disabled => unreachable!("Report server should be disabled"),
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
    rx.for_each(move |file| {
        let stat_event = stats
            .clone()
            .send(Event::InventoryReceived)
            .map_err(|e| error!("receive error: {}", e; "component" => LogComponent::Watcher))
            .map(|_| ());
        // FIXME: no need for a spawn
        tokio::spawn(lazy(|| stat_event));

        debug!("received: {:?}", file; "component" => LogComponent::Watcher);
        let treat_file = match job_config.cfg.processing.inventory.output {
            InventoryOutputSelect::Upstream => output_report_database(file, job_config.clone()),
            // The job should not be started in this case
            InventoryOutputSelect::Disabled => unreachable!("Inventory server should be disabled"),
        };

        tokio::spawn(lazy(|| treat_file));
        Ok(())
    })
}

fn output_report_database(
    path: ReceivedFile,
    job_config: Arc<JobConfig>,
) -> impl Future<Item = (), Error = ()> {
    // Everything here is blocking: reading on disk or inserting into database
    // We could use tokio::fs but it works the same and only makes things
    // more complicated.
    // We can switch to it once we also have stream (i.e. not on disk) input.
    poll_fn(move || {
        blocking(|| {
            output_report_database_inner(&path, &job_config)
                .map_err(|e| error!("output error: {}", e; "component" => LogComponent::Database))
        })
        .map_err(|_| panic!("the thread pool shut down"))
    })
    .flatten()
}

fn output_report_database_inner(
    path: &ReceivedFile,
    job_config: &Arc<JobConfig>,
) -> Result<(), Error> {
    // blocking by essence
    debug!("Starting insertion of {:#?}", path);
    let runlog = read_file_content(&path)?.parse::<RunLog>()?;

    let _inserted = insert_runlog(
        &job_config
            .pool
            .clone()
            .expect("output uses database but no config provided"),
        &runlog,
        InsertionBehavior::SkipDuplicate,
    )?;
    Ok(())
}
