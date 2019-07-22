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
    configuration::main::ReportingOutputSelect,
    data::{RunInfo, RunLog},
    error::Error,
    input::{read_compressed_file, signature, watch::*},
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
use md5::{Digest, Md5};
use std::os::unix::ffi::OsStrExt;
use std::{convert::TryFrom, path::PathBuf, sync::Arc};
use tokio::{
    fs::{remove_file, rename},
    prelude::*,
};
use tokio_threadpool::blocking;
use tracing::{debug, error, span, warn, Level};

pub type ReceivedFile = PathBuf;
pub type RootDirectory = PathBuf;

pub fn serve_reports(job_config: &Arc<JobConfig>, stats: &mpsc::Sender<Event>) {
    let report_span = span!(Level::TRACE, "reporting");
    let _report_enter = report_span.enter();

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

fn treat_reports(
    job_config: Arc<JobConfig>,
    rx: mpsc::Receiver<ReceivedFile>,
    stats: mpsc::Sender<Event>,
) -> impl Future<Item = (), Error = ()> {
    rx.for_each(move |file| {
        let queue_id = format!(
            "{:X}",
            Md5::digest(
                file.file_name()
                    .unwrap_or_else(|| file.as_os_str())
                    .as_bytes()
            )
        );
        let report_span = span!(
            Level::INFO,
            "report",
            queue_id = %queue_id,
        );
        let _report_enter = report_span.enter();

        let stat_event = stats
            .clone()
            .send(Event::ReportReceived)
            .map_err(|e| error!("receive error: {}", e))
            .map(|_| ());
        // FIXME: no need for a spawn
        tokio::spawn(lazy(|| stat_event));

        // Check run info
        let info = RunInfo::try_from(file.as_ref()).map_err(|e| warn!("received: {}", e))?;

        let node_span = span!(
            Level::INFO,
            "node",
            node_id = %info.node_id,
        );
        let _node_enter = node_span.enter();

        if !job_config
            .nodes
            .read()
            .expect("Cannot read nodes list")
            .is_subnode(&info.node_id)
        {
            let fail = fail(
                file,
                job_config.cfg.processing.reporting.directory.clone(),
                Event::ReportRefused,
                stats.clone(),
            );

            // FIXME: no need for a spawn
            tokio::spawn(lazy(|| fail));
            error!("refused: report from {:?}, unknown id", &info.node_id);
            return Err(());
        }

        debug!("received: {:?}", file);

        let stats_ok = stats.clone();
        let stats_err = stats.clone();
        let file_move = file.clone();
        let job_config_clone = job_config.clone();
        let treat_file = match job_config.cfg.processing.reporting.output {
            ReportingOutputSelect::Database => {
                output_report_database(file.clone(), info, job_config.clone())
                    .and_then(|_| success(file, Event::ReportInserted, stats_ok))
                    .or_else(move |_| {
                        fail(
                            file_move,
                            job_config_clone.cfg.processing.reporting.directory.clone(),
                            Event::ReportRefused,
                            stats_err,
                        )
                    })
            }
            ReportingOutputSelect::Upstream => unimplemented!(),
            // The job should not be started in this case
            ReportingOutputSelect::Disabled => unreachable!("Report server should be disabled"),
        };

        tokio::spawn(lazy(|| treat_file));
        Ok(())
    })
}

fn success(
    file: ReceivedFile,
    event: Event,
    stats: mpsc::Sender<Event>,
) -> impl Future<Item = (), Error = ()> {
    stats
        .send(event)
        .map_err(|e| error!("send error: {}", e))
        .then(|_| {
            remove_file(file.clone())
                .map(move |_| debug!("deleted: {:#?}", file))
                .map_err(|e| error!("error: {}", e))
        })
}

fn fail(
    file: ReceivedFile,
    directory: RootDirectory,
    event: Event,
    stats: mpsc::Sender<Event>,
) -> impl Future<Item = (), Error = ()> {
    stats
        .send(event)
        .map_err(|e| error!("send error: {}", e))
        .then(move |_| {
            rename(
                file.clone(),
                directory
                    .join("failed")
                    .join(file.file_name().expect("not a file")),
            )
            .map(move |_| {
                debug!(
                    "moved: {:#?} to {:#?}",
                    file,
                    directory
                        .join("failed")
                        .join(file.file_name().expect("not a file"))
                )
            })
            .map_err(|e| error!("error: {}", e))
        })
}

fn output_report_database(
    path: ReceivedFile,
    run_info: RunInfo,
    job_config: Arc<JobConfig>,
) -> impl Future<Item = (), Error = ()> {
    // Everything here is blocking: reading on disk or inserting into database
    // We could use tokio::fs but it works the same and only makes things
    // more complicated.
    // We can switch to it once we also have stream (i.e. not on disk) input.
    poll_fn(move || {
        blocking(|| {
            output_report_database_inner(&path, &run_info, &job_config)
                .map_err(|e| error!("output error: {}", e))
        })
        .map_err(|_| panic!("the thread pool shut down"))
    })
    .flatten()
}

fn output_report_database_inner(
    path: &ReceivedFile,
    run_info: &RunInfo,
    job_config: &Arc<JobConfig>,
) -> Result<(), Error> {
    debug!("Starting insertion of {:#?}", path);

    let signed_runlog = signature(
        &read_compressed_file(&path)?,
        job_config
            .clone()
            .nodes
            .read()
            .expect("read nodes")
            .certs(&run_info.node_id)
            .ok_or_else(|| Error::MissingCertificateForNode(run_info.node_id.clone()))?,
    )?;

    let parsed_runlog = RunLog::try_from((run_info.clone(), signed_runlog.as_ref()))?;

    let filtered_runlog = if job_config.cfg.processing.reporting.skip_logs {
        parsed_runlog.whithout_logs()
    } else {
        parsed_runlog
    };

    let _inserted = insert_runlog(
        &job_config
            .pool
            .clone()
            .expect("output uses database but no config provided"),
        &filtered_runlog,
        InsertionBehavior::SkipDuplicate,
    )?;
    Ok(())
}
