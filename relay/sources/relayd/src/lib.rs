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

#[macro_use]
extern crate diesel;
#[macro_use]
extern crate structopt;

pub mod api;
pub mod configuration;
pub mod data;
pub mod error;
pub mod input;
pub mod output;
pub mod processing;
pub mod remote_run;
pub mod stats;
pub mod status;

use crate::{
    api::api,
    configuration::{
        CliConfiguration, Configuration, InventoryOutputSelect, LogConfig, OutputSelect,
        ReportingOutputSelect,
    },
    data::node::NodesList,
    error::Error,
    output::database::{pg_pool, PgPool},
    processing::serve_reports,
    stats::Stats,
};
use futures::{
    future::{lazy, Future},
    stream::Stream,
    sync::mpsc,
};
use lazy_static::lazy_static;
use std::{
    fs::create_dir_all,
    path::Path,
    process::exit,
    string::ToString,
    sync::{Arc, RwLock},
};
use structopt::clap::crate_version;
use tokio_signal::unix::{Signal, SIGHUP, SIGINT, SIGTERM};
use tracing::{debug, error, info};
use tracing_fmt::{
    default::NewRecorder,
    filter::{env::EnvFilter, reload::Handle},
    FmtSubscriber,
};
use tracing_log::LogTracer;

pub fn init_logger() -> Result<Handle<EnvFilter, NewRecorder>, Error> {
    let subscriber = FmtSubscriber::builder().with_filter_reloading().finish();
    let reload_handle = subscriber.reload_handle();
    // Set logger for global context
    tracing::subscriber::set_global_default(subscriber)?;

    // Set logger for dependencies using log
    lazy_static! {
        static ref LOGGER: LogTracer = LogTracer::with_filter(log::LevelFilter::Trace);
    }
    log::set_logger(&*LOGGER)?;
    log::set_max_level(log::LevelFilter::Trace);

    // Until actual config load
    reload_handle.reload("error")?;
    Ok(reload_handle)
}

pub fn check_configuration(cfg_dir: &Path) -> Result<(), Error> {
    Configuration::new(&cfg_dir)?;
    LogConfig::new(&cfg_dir)?;
    Ok(())
}

#[allow(clippy::cognitive_complexity)]
pub fn start(
    cli_cfg: CliConfiguration,
    reload_handle: Handle<EnvFilter, NewRecorder>,
) -> Result<(), Error> {
    // Start by setting log config
    let log_cfg = LogConfig::new(&cli_cfg.configuration_dir)?;
    reload_handle.reload(log_cfg.to_string())?;

    info!("Starting rudder-relayd {}", crate_version!());
    debug!("Parsed cli configuration:\n{:#?}", &cli_cfg);
    info!("Read configuration from {:#?}", &cli_cfg.configuration_dir);
    debug!("Parsed logging configuration:\n{:#?}", &log_cfg);

    // ---- Setup data structures ----

    let cfg = Configuration::new(cli_cfg.configuration_dir.clone())?;
    let cfg_dir = cli_cfg.configuration_dir.clone();
    let stats = Arc::new(RwLock::new(Stats::default()));
    let job_config = JobConfig::new(cli_cfg, cfg)?;

    // ---- Setup signal handlers ----

    debug!("Setup signal handlers");

    // SIGINT or SIGTERM: graceful shutdown
    let shutdown = Signal::new(SIGINT)
        .flatten_stream()
        .select(Signal::new(SIGTERM).flatten_stream())
        .into_future()
        .map(|_sig| {
            info!("Signal received: shutdown requested");
            exit(1);
        })
        .map_err(|e| error!("signal error {}", e.0));

    // SIGHUP: reload logging configuration + nodes list
    let job_config_reload = job_config.clone();

    let reload = Signal::new(SIGHUP)
        .flatten_stream()
        .map_err(|e| e.into())
        .for_each(move |_signal| {
            info!("Signal received: reload requested");

            LogConfig::new(&cfg_dir)
                .and_then(|log_cfg| {
                    reload_handle
                        .reload(log_cfg.to_string())
                        .map_err(|e| e.into())
                })
                .and_then(|_| job_config_reload.reload_nodeslist())
                .map_err(|e| {
                    error!("reload error {}", e);
                    e
                })
        })
        .map_err(|e| error!("signal error {}", e));

    // ---- Start server ----

    info!("Starting server");

    tokio::run(lazy(move || {
        let (tx_stats, rx_stats) = mpsc::channel(1_024);

        tokio::spawn(Stats::receiver(stats.clone(), rx_stats));
        tokio::spawn(api(
            job_config.cfg.general.listen,
            shutdown,
            job_config.clone(),
            stats.clone(),
        ));

        //tokio::spawn(shutdown);
        tokio::spawn(reload);

        if job_config.cfg.processing.reporting.output.is_enabled() {
            serve_reports(&job_config, &tx_stats);
        } else {
            info!("Skipping reporting as it is disabled");
        }

        Ok(())
    }));

    unreachable!("Server halted unexpectedly");
}

pub struct JobConfig {
    pub cli_cfg: CliConfiguration,
    pub cfg: Configuration,
    pub nodes: RwLock<NodesList>,
    pub pool: Option<PgPool>,
}

impl JobConfig {
    pub fn new(cli_cfg: CliConfiguration, cfg: Configuration) -> Result<Arc<Self>, Error> {
        // Create dirs
        if cfg.processing.inventory.output != InventoryOutputSelect::Disabled {
            create_dir_all(cfg.processing.inventory.directory.join("incoming"))?;
            create_dir_all(
                cfg.processing
                    .inventory
                    .directory
                    .join("accepted-nodes-updates"),
            )?;
            create_dir_all(cfg.processing.inventory.directory.join("failed"))?;
        }
        if cfg.processing.reporting.output != ReportingOutputSelect::Disabled {
            create_dir_all(cfg.processing.reporting.directory.join("incoming"))?;
            create_dir_all(cfg.processing.reporting.directory.join("failed"))?;
        }

        let pool = if cfg.processing.reporting.output == ReportingOutputSelect::Database {
            Some(pg_pool(&cfg.output.database)?)
        } else {
            None
        };

        let nodes = RwLock::new(NodesList::new(
            &cfg.general.nodes_list_file,
            Some(&cfg.general.nodes_certs_file),
        )?);

        Ok(Arc::new(Self {
            cli_cfg,
            cfg,
            nodes,
            pool,
        }))
    }

    pub fn reload_nodeslist(&self) -> Result<(), Error> {
        let mut nodes = self.nodes.write().expect("could not write nodes list");
        *nodes = NodesList::new(
            &self.cfg.general.nodes_list_file,
            Some(&self.cfg.general.nodes_certs_file),
        )?;
        Ok(())
    }
}
