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
    configuration::main::InventoryOutputSelect,
    input::watch::*,
    output::upstream::send_inventory,
    processing::{failure, success, OutputError, ReceivedFile},
    stats::Event,
    JobConfig,
};
use futures::{future::Future, lazy, sync::mpsc, Stream};
use md5::{Digest, Md5};
use std::{os::unix::ffi::OsStrExt, sync::Arc};
use tokio::prelude::*;
use tracing::{debug, error, info, span, Level};

static INVENTORY_EXTENSIONS: &[&str] = &["gz", "xml", "sign"];

#[derive(Debug, Copy, Clone)]
pub enum InventoryType {
    New,
    Update,
}

pub fn start(job_config: &Arc<JobConfig>, stats: &mpsc::Sender<Event>) {
    let span = span!(Level::TRACE, "inventory");
    let _enter = span.enter();

    let (sender, receiver) = mpsc::channel(1_024);
    tokio::spawn(serve(
        job_config.clone(),
        receiver,
        InventoryType::New,
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
        &sender,
    );
    let (sender, receiver) = mpsc::channel(1_024);
    tokio::spawn(serve(
        job_config.clone(),
        receiver,
        InventoryType::Update,
        stats.clone(),
    ));
    watch(
        &job_config
            .cfg
            .processing
            .inventory
            .directory
            .join("accepted-nodes-updates"),
        &job_config,
        &sender,
    );
}

fn serve(
    job_config: Arc<JobConfig>,
    rx: mpsc::Receiver<ReceivedFile>,
    inventory_type: InventoryType,
    stats: mpsc::Sender<Event>,
) -> impl Future<Item = (), Error = ()> {
    rx.for_each(move |file| {
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
            "inventory",
            queue_id = %queue_id,
        );
        let _enter = span.enter();

        let stat_event = stats
            .clone()
            .send(Event::InventoryReceived)
            .map_err(|e| error!("receive error: {}", e))
            .map(|_| ());
        // FIXME: no need for a spawn
        tokio::spawn(lazy(|| stat_event));

        debug!("received: {:?}", file);

        let treat_file: Box<dyn Future<Item = (), Error = ()> + Send> =
            match job_config.cfg.processing.inventory.output {
                InventoryOutputSelect::Upstream => output_inventory_upstream(
                    file.clone(),
                    inventory_type,
                    job_config.clone(),
                    stats.clone(),
                ),
                // The job should not be started in this case
                InventoryOutputSelect::Disabled => {
                    unreachable!("Inventory server should be disabled")
                }
            };

        tokio::spawn(lazy(|| treat_file));
        Ok(())
    })
}

fn output_inventory_upstream(
    path: ReceivedFile,
    inventory_type: InventoryType,
    job_config: Arc<JobConfig>,
    stats: mpsc::Sender<Event>,
) -> Box<dyn Future<Item = (), Error = ()> + Send> {
    let job_config_clone = job_config.clone();
    let path_clone2 = path.clone();
    let stats_clone = stats.clone();
    Box::new(
        send_inventory(job_config.clone(), path.clone(), inventory_type)
            .map_err(|e| {
                error!("output error: {}", e);
                OutputError::from(e)
            })
            .or_else(move |e| match e {
                OutputError::Permanent => failure(
                    path_clone2.clone(),
                    job_config_clone
                        .clone()
                        .cfg
                        .processing
                        .inventory
                        .directory
                        .clone(),
                    Event::InventoryRefused,
                    stats.clone(),
                ),
                OutputError::Transient => {
                    info!("transient error, skipping");
                    Box::new(futures::future::err::<(), ()>(()))
                }
            })
            .and_then(move |_| success(path.clone(), Event::InventorySent, stats_clone.clone())),
    )
}
