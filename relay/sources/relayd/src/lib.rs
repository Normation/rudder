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

pub mod api;
pub mod configuration;
pub mod data;
pub mod error;
pub mod input;
pub mod output;
pub mod processing;
pub mod stats;

use crate::{
    api::api,
    configuration::{
        CliConfiguration, Configuration, InventoryOutputSelect, LogConfig, ReportingOutputSelect,
    },
    data::node,
    error::Error,
    output::database::{pg_pool, PgPool},
    processing::{serve_inventories, serve_reports},
    stats::Stats,
};
use clap::crate_version;
use futures::{
    future::{lazy, Future},
    stream::Stream,
    sync::mpsc,
};
use slog::{o, slog_debug, slog_error, slog_info, Drain, Level, Logger};
use slog_async::Async;
use slog_atomic::{AtomicSwitch, AtomicSwitchCtrl};
use slog_kvfilter::KVFilter;
use slog_scope::{debug, error, info, GlobalLoggerGuard};
use slog_term::{CompactFormat, TermDecorator};
use std::iter::FromIterator;
use std::process::exit;
use std::string::ToString;
use std::{
    collections::{HashSet, HashMap},
    path::Path,
    sync::{Arc, RwLock},
    fs::create_dir_all,
};
use tokio_signal::unix::{Signal, SIGHUP, SIGINT, SIGTERM};

pub fn init(cli_cfg: &CliConfiguration) -> Result<(), Error> {
    // ---- Load configuration ----

    let cfg = Configuration::new(&cli_cfg.configuration_file)?;

    if cli_cfg.check_configuration {
        println!("Syntax: OK");
        return Ok(());
    }

    // ---- Setup loggers ----

    let log_ctrl = LoggerCtrl::new();
    log_ctrl.load(&cfg.logging);

    // ---- Start execution ----

    info!("Starting rudder-relayd {}", crate_version!());

    debug!("Parsed cli configuration:\n{:#?}", &cli_cfg);
    info!("Read configuration from {:#?}", &cli_cfg.configuration_file);
    debug!("Parsed configuration:\n{:#?}", &cfg);

    // ---- Setup data structures ----

    let stats = Arc::new(RwLock::new(Stats::default()));
    let job_config = JobConfig::new(&cli_cfg.configuration_file)?;

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
    let cfg_file = cli_cfg.configuration_file.clone();
    let job_config_reload = job_config.clone();
    let reload = Signal::new(SIGHUP)
        .flatten_stream()
        .for_each(move |_signal| {
            info!("Signal received: reload requested");
            match Configuration::new(&cfg_file) {
                Ok(cfg) => {
                    debug!("Parsed configuration:\n{:#?}", &cfg);
                    log_ctrl.load(&cfg.logging);
                    match job_config_reload.reload_nodeslist() {
                        Ok(_) => (),
                        Err(e) => error!("nodes list reload error {}", e),
                    }
                }
                Err(e) => error!("config reload error {}", e),
            };
            Ok(())
        })
        .map_err(|e| error!("signal error {}", e));

    // ---- Start server ----

    tokio::run(lazy(move || {
        let (tx_stats, rx_stats) = mpsc::channel(1_024);

        tokio::spawn(Stats::receiver(stats.clone(), rx_stats));
        tokio::spawn(api(cfg.general.listen, shutdown, stats.clone()));

        //tokio::spawn(shutdown);
        tokio::spawn(reload);

        if job_config.cfg.processing.reporting.output != ReportingOutputSelect::Disabled {
            serve_reports(&job_config, &tx_stats);
        }
        if job_config.cfg.processing.inventory.output != InventoryOutputSelect::Disabled {
            serve_inventories(&job_config, &tx_stats);
        }
        Ok(())
    }));

    unreachable!("Server halted unexpectedly");
}

pub struct LoggerCtrl {
    ctrl: AtomicSwitchCtrl,
    #[allow(clippy::used_underscore_binding)]
    _guard: GlobalLoggerGuard,
}

impl LoggerCtrl {
    #[allow(clippy::new_without_default)]
    pub fn new() -> Self {
        let drain = AtomicSwitch::new(Self::logger_drain());
        let ctrl = drain.ctrl();
        let log = Logger::root(drain.fuse(), o!());
        // Make sure to save the guard
        let _guard = slog_scope::set_global_logger(log);
        // Integrate libs using standard log crate
        slog_stdlog::init().expect("Could not initialize standard logging");

        #[allow(clippy::used_underscore_binding)]
        Self { ctrl, _guard }
    }

    fn logger_drain() -> slog::Fuse<slog_async::Async> {
        let decorator = TermDecorator::new().stdout().build();
        let drain = CompactFormat::new(decorator).build().fuse();
        Async::new(drain)
            .thread_name("relayd-logger".to_string())
            .chan_size(2048)
            .build()
            .fuse()
    }

    pub fn load(&self, cfg: &LogConfig) {
        if cfg.general.level == Level::Trace {
            // No filter at all if general level is trace.
            // This needs to be handled separately as KVFilter cannot skip
            // its filters completely.
            self.ctrl.set(Self::logger_drain());
        } else {
            let mut node_filter = HashMap::new();
            node_filter.insert("node".to_string(), cfg.filter.nodes.clone());
            node_filter.insert(
                "component".to_string(),
                HashSet::from_iter(
                    cfg.filter
                        .components
                        .clone()
                        .iter()
                        .map(ToString::to_string),
                ),
            );
            let drain = KVFilter::new(
                slog::LevelFilter::new(Self::logger_drain(), cfg.filter.level),
                // decrement because the user provides the log level they want to see
                // while this displays logs unconditionally above the given level included.
                match cfg.general.level {
                    Level::Critical => Level::Error,
                    Level::Error => Level::Warning,
                    Level::Warning => Level::Info,
                    Level::Info => Level::Debug,
                    Level::Debug => Level::Trace,
                    Level::Trace => unreachable!("Global trace log level is handled separately"),
                },
            )
            .only_pass_any_on_all_keys(Some(node_filter.clone()));
            self.ctrl.set(drain.map(slog::Fuse));
            debug!("Log filters are {:#?}", node_filter);
        }
    }
}

pub struct JobConfig {
    pub cfg: Configuration,
    pub nodes: RwLock<node::List>,
    pub pool: Option<PgPool>,
}

impl JobConfig {
    pub fn new(configuration_file: &Path) -> Result<Arc<Self>, Error> {
        let cfg = Configuration::new(configuration_file)?;

        // Create dirs
        if cfg.processing.inventory.output != InventoryOutputSelect::Disabled {
            create_dir_all(cfg.processing.inventory.directory.join("incoming"))?;
            create_dir_all(cfg.processing.inventory.directory.join("accepted-nodes-updates"))?;
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
        let nodes = RwLock::new(node::List::new(&cfg.general.nodes_list_file)?);

        Ok(Arc::new(Self { cfg, nodes, pool }))
    }

    pub fn reload_nodeslist(&self) -> Result<(), Error> {
        let mut nodes = self.nodes.write().expect("could not write nodes list");
        *nodes = node::List::new(&self.cfg.general.nodes_list_file)?;
        Ok(())
    }
}

