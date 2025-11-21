// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use std::{convert::TryFrom, sync::Arc};

use anyhow::Error;
use tokio::{sync::mpsc, task::spawn_blocking};
use tracing::{debug, error, info, instrument, span, warn, Instrument, Level};

use crate::{
    configuration::main::ReportingOutputSelect,
    data::{RunInfo, RunLog},
    input::{read_compressed_file, verify_signature, watch::*},
    metrics::{REPORTS, REPORTS_PROCESSING_DURATION, REPORTS_SIZE_BYTES},
    output::{
        database::{insert_runlog, RunlogInsertion},
        upstream::send_report,
    },
    processing::{failure, queue_id_from_file, success, OutputError, ReceivedFile},
    JobConfig,
};

static REPORT_EXTENSIONS: &[&str] = &["gz", "zip", "log"];

#[instrument(name = "reporting", level = "debug", skip(job_config))]
pub fn start(job_config: &Arc<JobConfig>) {
    let incoming_path = job_config
        .cfg
        .processing
        .reporting
        .directory
        .join("incoming");
    let failed_path = job_config.cfg.processing.reporting.directory.join("failed");

    let (sender, receiver) = mpsc::channel(1_024);
    tokio::spawn(cleanup(
        incoming_path.clone(),
        job_config.cfg.processing.reporting.cleanup,
    ));
    tokio::spawn(cleanup(
        failed_path,
        job_config.cfg.processing.reporting.cleanup,
    ));
    tokio::spawn(serve(job_config.clone(), receiver));
    watch(
        incoming_path,
        job_config.cfg.processing.reporting.catchup,
        sender,
    );
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
            continue;
        }

        let queue_id = queue_id_from_file(&file);
        let span = span!(
            Level::INFO,
            "report",
            queue_id = %queue_id,
        );

        // Check run info
        // FIXME make async
        let info = match RunInfo::try_from(file.as_ref()) {
            Ok(r) => r,
            Err(e) => {
                warn!("received: {:?}", e);
                continue;
            }
        };

        let node_span = span!(
            Level::INFO,
            "node",
            node_id = %info.node_id,
        );

        handle_report(job_config.clone(), info, file)
            .instrument(span)
            .instrument(node_span)
            .await
    }
    Ok(())
}

async fn handle_report(job_config: Arc<JobConfig>, info: RunInfo, file: ReceivedFile) {
    if !job_config.nodes.read().await.is_subnode(&info.node_id) {
        REPORTS.with_label_values(&["invalid"]).inc();
        failure(file, job_config.cfg.processing.reporting.directory.clone())
            .await
            .unwrap_or_else(|e| error!("output error: {}", e));

        error!("refused: report from {:?}, unknown id", &info.node_id);
        // this is actually expected behavior
        return;
    }

    debug!("received: {:?}", file);

    match job_config.cfg.processing.reporting.output {
        ReportingOutputSelect::Database => {
            output_report_database(file, info, job_config.clone()).await
        }
        ReportingOutputSelect::Upstream => output_report_upstream(file, job_config.clone()).await,
        // The job should not be started in this case
        ReportingOutputSelect::Disabled => {
            unreachable!("Report server should be disabled")
        }
    }
    .unwrap_or_else(|e| error!("output error: {}", e));
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
    let signed_runlog = verify_signature(
        &content,
        job_config.nodes.read().await.certs(&run_info.node_id)?,
        &job_config.ca_store,
        job_config.validate_certificates,
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
        )
    })
    .await?;

    timer.observe_duration();
    result
}
