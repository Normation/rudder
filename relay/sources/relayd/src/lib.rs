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
pub mod metrics;
pub mod output;
pub mod processing;

use crate::{
    configuration::{
        cli::CliConfiguration,
        logging::LogConfig,
        main::{Configuration, InventoryOutputSelect, OutputSelect, ReportingOutputSelect},
    },
    data::node::{NodeId, NodesList},
    metrics::{MANAGED_NODES, SUB_NODES},
    output::database::{pg_pool, PgPool},
    processing::{inventory, reporting},
};
use anyhow::Error;
use configuration::main::CertificateVerificationModel;
use lazy_static::lazy_static;
use reqwest::{Certificate, Client};
use std::{
    collections::HashMap, fs, fs::create_dir_all, path::Path, process::exit, string::ToString,
    sync::Arc,
};
use structopt::clap::crate_version;
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

lazy_static! {
    static ref USER_AGENT: String = format!("rudder-relayd/{}", crate_version!());
}

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

    // Spawn metrics
    metrics::register();

    // ---- Setup data structures ----

    let cfg = Configuration::new(cli_cfg.configuration_dir.clone())?;
    cfg.validate()?;
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

// Graceful reload/restart
//
// If we reload parts of the config we need to reload everything.
// That means restarting all tokio tasks.
//
// We could use a channel to tell all tasks to finish what they are doing and stop.
//
// Potentially remote-run could take minutes to run, we need to decide what to do in this case.
//
// Tasks could start making a copy if the config they use to allow correct reload.
//

pub struct JobConfig {
    /// Does not reload, by definition
    pub cli_cfg: CliConfiguration,

    pub cfg: Configuration,
    pub nodes: RwLock<NodesList>,
    pub pool: Option<PgPool>,
    /// Parent policy server
    pub upstream_client: Client,
    /// Sub relays
    // TODO could be lazily created
    pub downstream_clients: HashMap<NodeId, Client>,
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

        // HTTP client
        //

        // compute actual model
        let model = if !cfg.output.upstream.verify_certificates {
            warn!("output.upstream.verify_certificates parameter is deprecated, use general.certificate_verification_model instead");
            CertificateVerificationModel::DangerousNone
        } else {
            cfg.general.certificate_verification_model
        };

        let upstream_client = match model {
            CertificateVerificationModel::Rudder => {
                let cert = Certificate::from_pem(&fs::read(
                    &cfg.output.upstream.server_certificate_file,
                )?)?;
                Self::new_http_client(vec![cert])?
            }
            CertificateVerificationModel::System => Client::builder()
                .user_agent(USER_AGENT.clone())
                .https_only(true)
                .build()?,
            CertificateVerificationModel::DangerousNone => {
                warn!("Certificate verification is disabled, it should not be done in production");
                Client::builder()
                    .user_agent(USER_AGENT.clone())
                    .danger_accept_invalid_certs(true)
                    .build()?
            }
        };

        let nodes = NodesList::new(
            cfg.node_id()?,
            &cfg.general.nodes_list_file,
            Some(&cfg.general.nodes_certs_file),
        )?;

        let mut downstream_clients = HashMap::new();
        // remote-run is the only use-case for downstream requests
        if cfg.remote_run.enabled {
            for (id, certs) in nodes.my_sub_relays_certs() {
                let client = match model {
                    CertificateVerificationModel::Rudder => {
                        let certs = match certs {
                            Some(stack) => stack
                                .into_iter()
                                // certificate has already be parsed by openssl, assume it's correct
                                .map(|c| Certificate::from_pem(&c.to_pem().unwrap()).unwrap())
                                .collect(),
                            None => vec![],
                        };
                        Self::new_http_client(certs)?
                    }
                    CertificateVerificationModel::System => Client::builder()
                        .user_agent(USER_AGENT.clone())
                        .https_only(true)
                        .build()?,
                    CertificateVerificationModel::DangerousNone => {
                        warn!(
                        "Certificate verification is disabled, it should not be done in production"
                        );
                        Client::builder()
                            .user_agent(USER_AGENT.clone())
                            .danger_accept_invalid_certs(true)
                            .build()?
                    }
                };
                downstream_clients.insert(id, client);
            }
        }

        let nodes = RwLock::new(nodes);

        Ok(Arc::new(Self {
            cli_cfg,
            cfg,
            nodes,
            pool,
            handle,
            upstream_client,
            downstream_clients,
        }))
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

    fn reload_logging(&self) -> Result<(), Error> {
        LogConfig::new(&self.cli_cfg.configuration_dir).and_then(|log_cfg| {
            self.handle
                .reload(EnvFilter::try_new(log_cfg.to_string())?)
                .map_err(|e| e.into())
        })
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
        self.reload_metrics().await;
        Ok(())
    }

    // With Rudder cert model we currently need one client for each host we talk to
    //
    // Not efficient in "System" case, but it's deprecated anyway
    fn new_http_client(certs: Vec<Certificate>) -> Result<Client, Error> {
        let mut client = Client::builder().user_agent(USER_AGENT.clone());

        client = client
            // Let's enforce https to prevent misconfigurations
            .https_only(true)
            .danger_accept_invalid_hostnames(true)
            .tls_built_in_root_certs(false);
        for cert in certs {
            client = client.add_root_certificate(cert);
        }

        Ok(client.build()?)
    }
}
