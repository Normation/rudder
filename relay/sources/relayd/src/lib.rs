// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

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
pub mod stats;

use crate::{
    configuration::{
        cli::CliConfiguration,
        logging::LogConfig,
        main::{Configuration, InventoryOutputSelect, OutputSelect, ReportingOutputSelect},
    },
    data::node::NodesList,
    output::database::{pg_pool, PgPool},
    processing::{inventory, reporting},
    stats::Stats,
};
use anyhow::Error;
use reqwest::Client;
use std::{
    fs::create_dir_all,
    path::Path,
    process::exit,
    string::ToString,
    sync::{Arc, RwLock},
};
use structopt::clap::crate_version;
use tokio::{
    signal::unix::{signal, SignalKind},
    sync::mpsc,
};
use tracing::{debug, error, info, warn};
use tracing_subscriber::{
    filter::EnvFilter,
    fmt::{
        format::{DefaultFields, Format, Full},
        Formatter, Subscriber,
    },
    reload::Handle,
};

// There are two main phases in execution:
//
// * Startup, when all config files are loaded, various structures are initialized,
// and tokio runtime is started. During startup, all errors make the program stop
// as all steps are necessary to correctly start the app.
// * Run, once everything is started. Errors are caught and logged, except
// for panics which stop the program
//
// The program should generally only be automatically restarted when
// encountering an unexpected error (panic). Start errors require external
// actions.

pub enum ExitStatus {
    /// Expected shutdown
    Shutdown,
    /// Unexpected crash (=panic in tokio)
    Crash,
    /// Could not start properly due to an error
    StartError(Error),
}

impl ExitStatus {
    /// Exit with code based on error type
    pub fn code(&self) -> i32 {
        match self {
            ExitStatus::Shutdown => 0,
            ExitStatus::Crash => 1,
            ExitStatus::StartError(e) => match e.downcast_ref::<toml::de::Error>() {
                // Configuration file error
                Some(_e) => 2,
                // Other error
                None => 3,
            },
        }
    }
}

type LogHandle =
    Handle<EnvFilter, Formatter<DefaultFields, Format<Full, ()>, fn() -> std::io::Stdout>>;

pub fn init_logger() -> Result<LogHandle, Error> {
    let builder = Subscriber::builder()
        .without_time()
        // Until actual config load
        .with_env_filter("error")
        .with_filter_reloading();
    let reload_handle = builder.reload_handle();
    builder.init();

    Ok(reload_handle)
}

pub fn check_configuration(cfg_dir: &Path) -> Result<(), Error> {
    Configuration::new(&cfg_dir)?;
    LogConfig::new(&cfg_dir)?;
    Ok(())
}

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

    // ---- Start server ----

    // Optimize for big servers: use multi-threaded scheduler

    let mut builder = tokio::runtime::Builder::new();
    if let Some(threads) = job_config.cfg.general.core_threads {
        builder.core_threads(threads);
    }
    if let Some(threads) = job_config.cfg.general.max_threads {
        builder.max_threads(threads);
    }
    if let Some(threads) = job_config.cfg.general.blocking_threads {
        warn!("blocking_threads is deprecated, replaced by max_threads");

        if job_config.cfg.general.max_threads.is_some() {
            warn!("max_threads was provided, ignoring blocking_threads");
        } else {
            warn!("using blocking_threads value as max_threads");
            // max_threads ~= core_threads + blocking_threads
            // and core_threads (=num cores) << blocking_threads so it is should
            // be good enough to approximate
            builder.max_threads(threads);
        }
    }
    let mut runtime = builder.threaded_scheduler().enable_all().build()?;

    // TODO: recheck panic/error behavior on tokio 0.2
    // don't use block_on_all as it panics on main future panic but not others
    runtime.block_on(async {
        // Setup signal handlers first
        let job_config_reload = job_config.clone();
        signal_handlers(job_config_reload);

        // Spawn stats system
        let (tx_stats, rx_stats) = mpsc::channel(1_024);
        tokio::spawn(Stats::receiver(stats.clone(), rx_stats));

        // Spawn report and inventory processing
        if job_config.cfg.processing.reporting.output.is_enabled() {
            reporting::start(&job_config, tx_stats.clone());
        } else {
            info!("Skipping reporting as it is disabled");
        }
        if job_config.cfg.processing.inventory.output.is_enabled() {
            inventory::start(&job_config, tx_stats);
        } else {
            info!("Skipping inventory as it is disabled");
        }

        // API should never return
        api::run(job_config.clone(), stats.clone())
            .await
            .expect("could not start api");
    });

    panic!("Server halted unexpectedly");
}

fn signal_handlers(job_config: Arc<JobConfig>) {
    // SIGHUP: reload logging configuration + nodes list
    tokio::spawn(async move {
        debug!("Setup configuration reload signal handler");

        let mut hangup = signal(SignalKind::hangup()).expect("Error setting up interrupt signal");
        loop {
            hangup.recv().await;
            let _ = job_config
                .reload()
                .map_err(|e| error!("reload error {}", e));
        }
    });

    // SIGINT or SIGTERM: immediate shutdown
    // TODO: graceful shutdown
    tokio::spawn(async {
        debug!("Setup shutdown signal handler");

        let mut terminate =
            signal(SignalKind::terminate()).expect("Error setting up interrupt signal");
        let mut interrupt =
            signal(SignalKind::interrupt()).expect("Error setting up interrupt signal");
        tokio::select! {
            _ = terminate.recv() => {
                info!("SIGINT received: shutdown requested");
            },
            _ = interrupt.recv() => {
                info!("SIGTERM received: shutdown requested");
            }
        }
        exit(ExitStatus::Shutdown.code());
    });
}

pub struct JobConfig {
    pub cli_cfg: CliConfiguration,
    pub cfg: Configuration,
    pub nodes: RwLock<NodesList>,
    pub pool: Option<PgPool>,
    pub client: Client,
    handle: LogHandle,
}

impl JobConfig {
    pub fn new(
        cli_cfg: CliConfiguration,
        cfg: Configuration,
        handle: LogHandle,
    ) -> Result<Arc<Self>, Error> {
        // Create needed directories
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

        let client = Client::builder()
            .danger_accept_invalid_certs(!cfg.output.upstream.verify_certificates)
            .build()?;

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
                .reload(EnvFilter::try_new(log_cfg.to_string())?)
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
