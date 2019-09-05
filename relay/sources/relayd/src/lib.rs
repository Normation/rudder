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
pub mod hashing;
pub mod input;
pub mod output;
pub mod processing;
pub mod remote_run;
pub mod shared_files;
pub mod shared_folder;
pub mod stats;
pub mod status;

use crate::{
    api::api,
    configuration::{
        cli::CliConfiguration,
        logging::LogConfig,
        main::{Configuration, InventoryOutputSelect, OutputSelect, ReportingOutputSelect},
    },
    data::node::NodesList,
    error::Error,
    output::database::{pg_pool, PgPool},
    processing::{inventory, reporting},
    stats::Stats,
};
use futures::{
    future::{lazy, Future},
    stream::Stream,
    sync::mpsc,
};
use reqwest::r#async::Client;
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
use tracing_log::LogTracer;
use tracing_subscriber::fmt::format::Format;
use tracing_subscriber::fmt::format::Full;
use tracing_subscriber::fmt::format::NewRecorder;
use tracing_subscriber::{filter::Filter, fmt::Subscriber, reload::Handle};

type LogHandle = Handle<Filter, Subscriber<NewRecorder, Format<Full, ()>, fn() -> std::io::Stdout>>;

pub fn init_logger() -> Result<LogHandle, Error> {
    let builder = Subscriber::builder()
        .without_time()
        // Until actual config load
        .with_filter("error")
        .with_filter_reloading();
    let reload_handle = builder.reload_handle();
    let subscriber = builder.finish();
    // Set logger for global context
    tracing::subscriber::set_global_default(subscriber)?;

    // Set logger for dependencies using log
    LogTracer::init()?;

    Ok(reload_handle)
}

pub fn check_configuration(cfg_dir: &Path) -> Result<(), Error> {
    Configuration::new(&cfg_dir)?;
    LogConfig::new(&cfg_dir)?;
    Ok(())
}

#[allow(clippy::cognitive_complexity)]
pub fn start(cli_cfg: CliConfiguration, reload_handle: LogHandle) -> Result<(), Error> {
    // Start by setting log config
    let log_cfg = LogConfig::new(&cli_cfg.configuration_dir)?;
    reload_handle.reload(log_cfg.to_string())?;

    info!("Starting rudder-relayd {}", crate_version!());
    debug!("Parsed cli configuration:\n{:#?}", &cli_cfg);
    info!("Read configuration from {:#?}", &cli_cfg.configuration_dir);
    debug!("Parsed logging configuration:\n{:#?}", &log_cfg);

    // ---- Setup data structures ----

    let cfg = Configuration::new(cli_cfg.configuration_dir.clone())?;
    let stats = Arc::new(RwLock::new(Stats::default()));
    let job_config = JobConfig::new(cli_cfg, cfg, reload_handle)?;

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
        .for_each(move |_signal| job_config_reload.reload())
        .map_err(|e| error!("signal error {}", e));

    // ---- Start server ----

    info!("Starting server");

    let mut builder = tokio::runtime::Builder::new();
    if let Some(threads) = job_config.cfg.general.core_threads {
        builder.core_threads(threads);
    }
    let mut runtime = builder
        .blocking_threads(job_config.cfg.general.blocking_threads)
        .build()?;

    runtime.spawn(lazy(move || {
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
            reporting::start(&job_config, &tx_stats);
        } else {
            info!("Skipping reporting as it is disabled");
        }

        if job_config.cfg.processing.inventory.output.is_enabled() {
            inventory::start(&job_config, &tx_stats);
        } else {
            info!("Skipping inventory as it is disabled");
        }

        Ok(())
    }));
    runtime
        .shutdown_on_idle()
        .wait()
        .expect("Server shutdown failed");

    unreachable!("Server halted unexpectedly");
}

pub struct JobConfig {
    pub cli_cfg: CliConfiguration,
    pub cfg: Configuration,
    pub nodes: RwLock<NodesList>,
    pub pool: Option<PgPool>,
    pub client: Option<Client>,
    handle: LogHandle,
}

impl JobConfig {
    pub fn new(
        cli_cfg: CliConfiguration,
        cfg: Configuration,
        handle: LogHandle,
    ) -> Result<Arc<Self>, Error> {
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

        let client = if cfg.processing.reporting.output == ReportingOutputSelect::Upstream
            || cfg.processing.inventory.output == InventoryOutputSelect::Upstream
        {
            Some(
                Client::builder()
                    .danger_accept_invalid_certs(!cfg.output.upstream.verify_certificates)
                    .build()?,
            )
        } else {
            None
        };

        let nodes = RwLock::new(NodesList::new(
            cfg.general.node_id.to_string(),
            &cfg.general.nodes_list_file,
            Some(&cfg.general.nodes_certs_file),
        )?);

        Ok(Arc::new(Self {
            cli_cfg,
            cfg,
            nodes,
            pool,
            handle,
            client,
        }))
    }

    fn reload_nodeslist(&self) -> Result<(), Error> {
        let mut nodes = self.nodes.write().expect("could not write nodes list");
        *nodes = NodesList::new(
            self.cfg.general.node_id.to_string(),
            &self.cfg.general.nodes_list_file,
            Some(&self.cfg.general.nodes_certs_file),
        )?;
        Ok(())
    }

    fn reload_logging(&self) -> Result<(), Error> {
        LogConfig::new(&self.cli_cfg.configuration_dir).and_then(|log_cfg| {
            self.handle
                .reload(Filter::try_new(log_cfg.to_string())?)
                .map_err(|e| e.into())
        })
    }

    pub fn reload(&self) -> Result<(), Error> {
        info!("Configuration reload requested");
        self.reload_logging()
            .and_then(|_| self.reload_nodeslist())
            .map_err(|e| {
                error!("reload error {}", e);
                e
            })
    }
}
