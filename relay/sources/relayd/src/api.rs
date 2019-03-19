use crate::stats::Stats;
use futures::Future;
use slog::slog_info;
use slog_scope::info;
use std::net::SocketAddr;
use std::sync::{Arc, RwLock};
use warp::Filter;

pub fn api(
    listen: SocketAddr,
    shutdown: impl Future<Item = ()> + Send + 'static,
    stats: Arc<RwLock<Stats>>,
) -> impl Future<Item = (), Error = ()> {
    let stats_simple =
        warp::path("stats").map(move || warp::reply::json(&(*stats.clone().read().unwrap())));
    let routes = warp::get2().and(stats_simple);
    let (addr, server) = warp::serve(routes).bind_with_graceful_shutdown(listen, shutdown);
    info!("Started stats API on {}", addr);
    server
}
