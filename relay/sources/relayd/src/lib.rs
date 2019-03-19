#[macro_use]
extern crate diesel;

pub mod api;
pub mod cli;
pub mod configuration;
pub mod data;
pub mod error;
pub mod fake;
pub mod input;
pub mod output;
pub mod stats;

use crate::{
    api::api,
    cli::parse,
    configuration::LogConfig,
    configuration::{Configuration, ReportingOutputSelect},
    data::nodes::parse_nodeslist,
    error::Error,
    input::serve,
    output::database::{pg_pool, PgPool},
    stats::Stats,
};
use data::nodes::NodesList;
use futures::{
    future::{lazy, Future},
    stream::Stream,
    sync::mpsc,
};
use slog::{o, slog_debug, slog_error, slog_info, slog_trace, Drain, Logger};
use slog_async::Async;
use slog_atomic::{AtomicSwitch, AtomicSwitchCtrl};
use slog_kvfilter::KVFilter;
use slog_scope::{debug, error, info, trace};
use slog_term::{CompactFormat, TermDecorator};
use stats::{stats_job, Event};
use std::collections::HashMap;
use std::{
    fs::read_to_string,
    path::Path,
    sync::{Arc, RwLock},
};
use tokio_signal::unix::{Signal, SIGHUP, SIGINT, SIGTERM};

pub struct JobConfig {
    pub cfg: Configuration,
    pub nodes: NodesList,
    pub pool: Option<PgPool>,
}

pub fn stats(rx: mpsc::Receiver<Event>) -> impl Future<Item = (), Error = ()> {
    let mut stats = Stats::default();
    rx.for_each(move |event| {
        stats.event(event);
        Ok(())
    })
}

pub fn load_configuration(file: &Path) -> Result<Configuration, Error> {
    info!("Reading configuration from {:#?}", file);
    Ok(Configuration::read_configuration(&read_to_string(file)?)?)
}

pub fn load_nodeslist(file: &Path) -> Result<NodesList, Error> {
    info!("Parsing nodes list from {:#?}", file);
    let nodes = parse_nodeslist(&read_to_string(file)?)?;
    trace!("Parsed nodes list:\n{:#?}", nodes);
    Ok(nodes)
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

fn load_loggers(ctrl: &AtomicSwitchCtrl, cfg: &LogConfig) {
    let mut node_filter = HashMap::new();
    node_filter.insert("node".to_string(), cfg.general.filter.nodes.clone());
    let drain = KVFilter::new(
        slog::LevelFilter::new(logger_drain(), cfg.general.filter.level),
        cfg.general.level,
    )
    .only_pass_any_on_all_keys(Some(node_filter));
    ctrl.set(drain.map(slog::Fuse));
}

pub fn start() -> Result<(), Error> {
    // ---- Default logger for fist steps ----

    let drain = AtomicSwitch::new(logger_drain());
    let ctrl = drain.ctrl();
    let log = Logger::root(drain.fuse(), o!());
    // Make sure to save the guard
    let _guard = slog_scope::set_global_logger(log);
    // Integrate libs using standard log crate
    slog_stdlog::init().unwrap();

    // ---- Process cli arguments ----

    let cli_cfg = parse();

    // ---- Load configuration ----

    let cfg = load_configuration(&cli_cfg.configuration_file)?;

    // ---- Setup loggers with actual configuration ----

    load_loggers(&ctrl, &cfg.logging);

    // ---- Start execution ----

    info!("Starting rudder relayd");

    debug!("Parsed cli configuration:\n{:#?}", &cli_cfg);
    debug!("Parsed configuration:\n{:#?}", &cfg);

    let nodes = load_nodeslist(&cfg.general.nodes_list_file)?;

    // ---- Setup signal handlers ----

    debug!("Setup signal handlers");

    // SIGINT or SIGTERM: graceful shutdown
    let shutdown = Signal::new(SIGINT)
        .flatten_stream()
        .select(Signal::new(SIGTERM).flatten_stream())
        .into_future()
        .map(|_sig| {
            info!("Signal received: shutdown requested");
            ::std::process::exit(1);
        })
        .map_err(|e| error!("signal error {}", e.0));

    // SIGHUP: reload logging configuration + nodes list
    let reload = Signal::new(SIGHUP)
        .flatten_stream()
        .for_each(move |_signal| {
            info!("Signal received: reload requested");
            let cfg = load_configuration(&cli_cfg.configuration_file.clone())
                .expect("Could not reload config");
            load_loggers(&ctrl, &cfg.logging);
            // TODO reload nodeslist
            Ok(())
        })
        .map_err(|e| error!("signal error {}", e));

    let stats = Arc::new(RwLock::new(Stats::default()));
    let http_api = api(cfg.general.listen, shutdown, stats.clone());

    let pool = if cfg.processing.reporting.output == ReportingOutputSelect::Database {
        Some(pg_pool(&cfg.output.database)?)
    } else {
        None
    };

    let job_config = Arc::new(JobConfig { cfg, nodes, pool });

    // ---- Start server ----

    tokio::run(lazy(move || {
        let (tx_stats, rx_stats) = mpsc::channel(1_024);

        tokio::spawn(stats_job(stats.clone(), rx_stats));
        tokio::spawn(http_api);

        //tokio::spawn(shutdown);
        tokio::spawn(reload);

        serve(job_config, tx_stats);
        Ok(())
    }));

    unreachable!("Server halted unexpectedly");
}
