// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

#[macro_use]
extern crate diesel;

use std::{
    collections::HashMap, fs, fs::create_dir_all, process::exit, string::ToString, sync::Arc,
};

use anyhow::Error;
use tokio::{
    signal::unix::{signal, SignalKind},
    sync::RwLock,
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

use crate::http_client::HttpClientSettings;
use crate::{
    configuration::{
        cli::CliConfiguration,
        logging::LogConfig,
        main::{Configuration, InventoryOutputSelect, OutputSelect, ReportingOutputSelect},
    },
    data::node::{NodeId, NodesList},
    http_client::HttpClient,
    metrics::{MANAGED_NODES, SUB_NODES},
    output::database::{pg_pool, PgPool},
    processing::{inventory, reporting, shared_files},
};

pub mod api;
pub mod configuration;
pub mod data;
pub mod error;
pub mod hashing;
pub mod http_client;
pub mod input;
pub mod metrics;
pub mod output;
pub mod processing;

pub const CRATE_NAME: &str = env!("CARGO_PKG_NAME");
pub const CRATE_VERSION: &str = env!("CARGO_PKG_VERSION");

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

pub fn start(
    cli_cfg: CliConfiguration,
    reload_handle: LogHandle,
    force_ports: Option<(u16, u16)>,
) -> Result<(), Error> {
    // Start by setting log config
    let log_cfg = LogConfig::new(&cli_cfg.config)?;
    reload_handle.reload(log_cfg.to_string())?;

    info!("Starting {} {}", CRATE_NAME, CRATE_VERSION);
    debug!("Parsed cli configuration:\n{:#?}", &cli_cfg);
    info!("Read configuration from {:#?}", &cli_cfg.config);
    debug!("Parsed logging configuration:\n{:#?}", &log_cfg);

    // Spawn metrics
    metrics::register();

    // ---- Setup data structures ----

    let mut cfg = Configuration::new(cli_cfg.config.clone())?;
    if let Some((api, https)) = force_ports {
        warn!("Overriding listen port to {api}");
        cfg.general.listen = format!("127.0.0.1:{api}");
        warn!("Overriding https port to {https}");
        cfg.general.https_port = https;
    }
    let job_config = JobConfig::new(cli_cfg, cfg, reload_handle)?;

    // ---- Start server ----

    // Optimize for big servers: use multi-threaded scheduler
    let mut builder = tokio::runtime::Builder::new_multi_thread();
    if let Some(threads) = job_config.cfg.general.core_threads {
        builder.worker_threads(threads);
    }
    if let Some(threads) = job_config.cfg.general.blocking_threads {
        builder.max_blocking_threads(threads);
    }
    let runtime = builder.enable_all().build()?;

    // TODO: recheck panic/error behavior on tokio 1.0
    // don't use block_on_all as it panics on main future panic but not others
    runtime.block_on(async {
        // Setup signal handlers first
        let job_config_reload = job_config.clone();
        signal_handlers(job_config_reload);

        // Spawn report and inventory processing
        if job_config.cfg.processing.reporting.output.is_enabled() {
            reporting::start(&job_config);
        } else {
            info!("Skipping reporting as it is disabled");
        }
        if job_config.cfg.processing.inventory.output.is_enabled() {
            inventory::start(&job_config);
        } else {
            info!("Skipping inventory as it is disabled");
        }

        // Spawn shared-files cleaner
        shared_files::start(&job_config);

        // Initialize metrics
        job_config.reload_metrics().await;

        // API should never return
        api::run(job_config.clone())
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
                .await
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
    /// Does not reload, by definition
    pub cli_cfg: CliConfiguration,
    pub cfg: Configuration,
    pub nodes: RwLock<NodesList>,
    pub pool: Option<PgPool>,
    pub http_client: RwLock<HttpClient>,
    handle: LogHandle,
}

impl JobConfig {
    fn create_dirs(cfg: &Configuration) -> Result<(), Error> {
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

        Ok(())
    }

    pub fn new(
        cli_cfg: CliConfiguration,
        cfg: Configuration,
        handle: LogHandle,
    ) -> Result<Arc<Self>, Error> {
        Self::create_dirs(&cfg)?;

        let pool = if cfg.processing.reporting.output == ReportingOutputSelect::Database {
            Some(pg_pool(&cfg.output.database)?)
        } else {
            None
        };

        let nodes = NodesList::new(
            cfg.node_id()?,
            &cfg.general.nodes_list_file,
            Some(&cfg.general.nodes_certs_file),
        )?;

        // HTTP clients
        //

        let http_settings = HttpClientSettings::try_from(&cfg)?;

        debug!("Creating HTTP client for upstream");
        let http_client = RwLock::new(HttpClient::new(http_settings)?);

        let nodes = RwLock::new(nodes);

        Ok(Arc::new(Self {
            cli_cfg,
            cfg,
            nodes,
            pool,
            handle,
            http_client,
        }))
    }

    fn reload_logging(&self) -> Result<(), Error> {
        LogConfig::new(&self.cli_cfg.config).and_then(|log_cfg| {
            self.handle
                .reload(EnvFilter::try_new(log_cfg.to_string())?)
                .map_err(|e| e.into())
        })
    }

    async fn reload_nodeslist(&self) -> Result<(), Error> {
        let mut nodes = self.nodes.write().await;
        *nodes = NodesList::new(
            self.cfg.node_id()?,
            &self.cfg.general.nodes_list_file,
            Some(&self.cfg.general.nodes_certs_file),
        )?;

        Ok(())
    }

    async fn reload_http_clients(&self) -> Result<(), Error> {
        let http_settings = HttpClientSettings::try_from(&self.cfg)?;
        let needs_reload = self.http_client.read().await.outdated(&http_settings);

        if needs_reload {
            info!("HTTP client configuration is outdated, reloading",);
            let mut upstream_client = self.http_client.write().await;
            *upstream_client = HttpClient::new(http_settings)?;
        } else {
            debug!("Upstream HTTP client has up-to-date certificate");
        }

        Ok(())
    }

    async fn reload_metrics(&self) {
        // Update nodes metrics
        let nodes = self.nodes.read().await;
        MANAGED_NODES.set(nodes.managed_nodes() as i64);
        SUB_NODES.set(nodes.sub_nodes() as i64);
    }

    pub async fn reload(&self) -> Result<(), Error> {
        info!("Configuration reload requested");
        self.reload_logging()?;
        self.reload_nodeslist().await?;
        // We need up-to-date certs
        // so run after nodes list refresh
        self.reload_http_clients().await?;
        // After reload for updated metrics
        self.reload_metrics().await;
        Ok(())
    }
}
