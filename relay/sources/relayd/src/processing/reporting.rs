// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use crate::{
    configuration::main::ReportingOutputSelect,
    data::{RunInfo, RunLog},
    input::{read_compressed_file, signature, watch::*},
    metrics::{REPORTS, REPORTS_PROCESSING_DURATION, REPORTS_SIZE_BYTES},
    output::{
        database::{insert_runlog, InsertionBehavior, RunlogInsertion},
        upstream::send_report,
    },
    processing::{failure, success, OutputError, ReceivedFile},
    JobConfig,
};
use anyhow::Error;
use md5::{Digest, Md5};
use std::{convert::TryFrom, os::unix::ffi::OsStrExt, sync::Arc};
use tokio::{sync::mpsc, task::spawn_blocking};
use tracing::{debug, error, info, span, warn, Level};

static REPORT_EXTENSIONS: &[&str] = &["gz", "zip", "log"];

pub fn start(job_config: &Arc<JobConfig>) {
    let span = span!(Level::TRACE, "reporting");
    let _enter = span.enter();

    let path = job_config
        .cfg
        .processing
        .reporting
        .directory
        .join("incoming");

    let (sender, receiver) = mpsc::channel(1_024);
    tokio::spawn(serve(job_config.clone(), receiver));
    tokio::spawn(cleanup(
        path.clone(),
        job_config.cfg.processing.reporting.cleanup,
    ));
    watch(path, job_config.cfg.processing.reporting.catchup, sender);
}

/// Should run forever except for fatal errors
async fn serve(job_config: Arc<JobConfig>, mut rx: mpsc::Receiver<ReceivedFile>) -> Result<(), ()> {
    while let Some(file) = rx.recv().await {
        // allows skipping temporary .dav files
        if !file
            .extension()
            .map(|f| REPORT_EXTENSIONS.contains(&f.to_string_lossy().as_ref()))
            .unwrap_or(false)
        {
            debug!(
                "skipping {:#?} as it does not have a known report extension",
                file
            );
            return Ok(());
        }

        let queue_id = format!(
            "{:X}",
            Md5::digest(
                file.file_name()
                    .unwrap_or_else(|| file.as_os_str())
                    .as_bytes()
            )
        );
        let span = span!(
            Level::INFO,
            "report",
            queue_id = %queue_id,
        );
        let _enter = span.enter();

        // Check run info
        let info = RunInfo::try_from(file.as_ref()).map_err(|e| warn!("received: {}", e))?;

        let node_span = span!(
            Level::INFO,
            "node",
            node_id = %info.node_id,
        );
        let _node_enter = node_span.enter();

        if !job_config.nodes.read().await.is_subnode(&info.node_id) {
            REPORTS.with_label_values(&["invalid"]).inc();
            failure(file, job_config.cfg.processing.reporting.directory.clone())
                .await
                .unwrap_or_else(|e| error!("output error: {}", e));

            error!("refused: report from {:?}, unknown id", &info.node_id);
            // this is actually expected behavior
            continue;
        }

        debug!("received: {:?}", file);

        match job_config.cfg.processing.reporting.output {
            ReportingOutputSelect::Database => {
                output_report_database(file, info, job_config.clone()).await
            }
            ReportingOutputSelect::Upstream => {
                output_report_upstream(file, job_config.clone()).await
            }
            // The job should not be started in this case
            ReportingOutputSelect::Disabled => unreachable!("Report server should be disabled"),
        }
        .unwrap_or_else(|e| error!("output error: {}", e));
    }
    Ok(())
}

async fn output_report_database(
    path: ReceivedFile,
    run_info: RunInfo,
    job_config: Arc<JobConfig>,
) -> Result<(), Error> {
    let path_clone = path.clone();
    let job_config_clone = job_config.clone();

    match output_report_database_inner(path, run_info, job_config).await {
        Ok(_) => {
            REPORTS.with_label_values(&["ok"]).inc();
            success(path_clone.clone()).await
        }
        Err(e) => {
            error!("output error: {}", e);
            match OutputError::from(e) {
                OutputError::Permanent => {
                    REPORTS.with_label_values(&["error"]).inc();
                    failure(
                        path_clone.clone(),
                        job_config_clone.cfg.processing.reporting.directory.clone(),
                    )
                    .await
                }
                OutputError::Transient => {
                    info!("transient error, skipping");
                    Ok(())
                }
            }
        }
    }
}

async fn output_report_upstream(
    path: ReceivedFile,
    job_config: Arc<JobConfig>,
) -> Result<(), Error> {
    let job_config_clone = job_config.clone();
    let path_clone2 = path.clone();

    let result = send_report(job_config, path.clone()).await;

    match result {
        Ok(_) => {
            REPORTS.with_label_values(&["forward_ok"]).inc();
            success(path.clone()).await
        }
        Err(e) => {
            error!("output error: {}", e);
            match OutputError::from(e) {
                OutputError::Permanent => {
                    REPORTS.with_label_values(&["forward_error"]).inc();
                    failure(
                        path_clone2.clone(),
                        job_config_clone.cfg.processing.reporting.directory.clone(),
                    )
                    .await
                }
                OutputError::Transient => {
                    info!("transient error, skipping");
                    Ok(())
                }
            }
        }
    }
}

async fn output_report_database_inner(
    path: ReceivedFile,
    run_info: RunInfo,
    job_config: Arc<JobConfig>,
) -> Result<RunlogInsertion, Error> {
    debug!("Starting insertion of {:#?}", path);
    let timer = REPORTS_PROCESSING_DURATION.start_timer();

    let content = read_compressed_file(&path).await?;
    let signed_runlog = signature(
        &content,
        job_config.nodes.read().await.certs(&run_info.node_id)?,
    )?;

    REPORTS_SIZE_BYTES.observe(signed_runlog.len() as f64);

    let parsed_runlog: RunLog = RunLog::try_from((run_info.clone(), signed_runlog.as_ref()))?;

    let filtered_runlog: RunLog = if !job_config
        .cfg
        .processing
        .reporting
        .skip_event_types
        .is_empty()
    {
        parsed_runlog.without_types(&job_config.cfg.processing.reporting.skip_event_types)
    } else {
        parsed_runlog
    };

    // Diesel uses blocking io, put it on the blocking threadpool
    let result = spawn_blocking(move || -> Result<RunlogInsertion, Error> {
        insert_runlog(
            &job_config
                .pool
                .clone()
                .expect("output uses database but no config provided"),
            &filtered_runlog,
            InsertionBehavior::SkipDuplicate,
        )
    })
    .await?;

    timer.observe_duration();
    result
}
