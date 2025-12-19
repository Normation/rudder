// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use std::sync::Arc;

use anyhow::Error;
use tokio::sync::mpsc;
use tracing::{debug, error, info, instrument, span, Instrument, Level};

use crate::processing::ensure_file_size_limit;
use crate::{
    configuration::main::InventoryOutputSelect,
    input::watch::*,
    metrics::INVENTORIES,
    output::upstream::send_inventory,
    processing::{failure, queue_id_from_file, success, OutputError, ReceivedFile},
    JobConfig,
};

static INVENTORY_EXTENSIONS: &[&str] = &["gz", "xml", "ocs", "sign"];

#[derive(Debug, Copy, Clone)]
pub enum InventoryType {
    New,
    Update,
}

#[instrument(name = "inventory", level = "debug", skip(job_config))]
pub fn start(job_config: &Arc<JobConfig>) {
    let (sender, receiver) = mpsc::channel(1_024);

    let incoming_path = job_config
        .cfg
        .processing
        .inventory
        .directory
        .join("incoming");
    tokio::spawn(serve(job_config.clone(), receiver, InventoryType::New));
    tokio::spawn(cleanup(
        incoming_path.clone(),
        job_config.cfg.processing.inventory.cleanup,
    ));
    watch(
        incoming_path,
        job_config.cfg.processing.inventory.catchup,
        sender,
    );

    let updates_path = job_config
        .cfg
        .processing
        .inventory
        .directory
        .join("accepted-nodes-updates");
    let (sender, receiver) = mpsc::channel(1_024);
    tokio::spawn(serve(job_config.clone(), receiver, InventoryType::Update));
    tokio::spawn(cleanup(
        updates_path.clone(),
        job_config.cfg.processing.inventory.cleanup,
    ));
    watch(
        updates_path,
        job_config.cfg.processing.inventory.catchup,
        sender,
    );
}

async fn serve(
    job_config: Arc<JobConfig>,
    mut rx: mpsc::Receiver<ReceivedFile>,
    inventory_type: InventoryType,
) -> Result<(), ()> {
    while let Some(file) = rx.recv().await {
        // allows skipping temporary .dav files
        if !file
            .extension()
            .map(|f| INVENTORY_EXTENSIONS.contains(&f.to_string_lossy().as_ref()))
            .unwrap_or(false)
        {
            debug!(
                "skipping {:#?} as it does not have a known inventory extension",
                file
            );
            continue;
        }

        match ensure_file_size_limit(
            file.clone(),
            job_config.cfg.processing.inventory.max_size,
            job_config.cfg.processing.inventory.directory.clone(),
        )
        .await
        {
            Ok(_) => (),
            Err(e) => {
                error!("{:?}", e);
                continue;
            }
        }

        let queue_id = queue_id_from_file(&file);
        debug!("received: {:?}", file);
        let span = span!(
            Level::INFO,
            "inventory",
            queue_id = %queue_id,
        );
        match job_config.cfg.processing.inventory.output {
            InventoryOutputSelect::Upstream => {
                output_inventory_upstream(file, inventory_type, job_config.clone())
                    .instrument(span)
                    .await
            }
            // The job should not be started in this case
            InventoryOutputSelect::Disabled => unreachable!("Inventory server should be disabled"),
        }
        .unwrap_or_else(|e| error!("output error: {}", e));
    }
    Ok(())
}

async fn output_inventory_upstream(
    path: ReceivedFile,
    inventory_type: InventoryType,
    job_config: Arc<JobConfig>,
) -> Result<(), Error> {
    let job_config_clone = job_config.clone();
    let path_clone2 = path.clone();

    let result = send_inventory(job_config, path.clone(), inventory_type).await;

    match result {
        Ok(_) => {
            INVENTORIES.with_label_values(&["forward_ok"]).inc();
            success(path.clone()).await
        }
        Err(e) => {
            error!("output error: {}", e);
            match OutputError::from(e) {
                OutputError::Permanent => {
                    INVENTORIES.with_label_values(&["forward_error"]).inc();

                    failure(
                        path_clone2.clone(),
                        job_config_clone.cfg.processing.inventory.directory.clone(),
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
