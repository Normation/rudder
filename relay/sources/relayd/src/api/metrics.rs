// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use crate::metrics::REGISTRY;
use warp::{
    filters::{method, BoxedFilter},
    path, Filter, Reply,
};

/// Special case for /metrics, standard for prometheus
pub fn routes() -> BoxedFilter<(impl Reply,)> {
    method::get()
        .and(path!("metrics"))
        .and_then(handlers::metrics)
        .boxed()
}

pub mod handlers {
    use super::*;
    use crate::api::RudderReject;
    use prometheus::proto::MetricFamily;
    use warp::{reject, Rejection, Reply};

    pub async fn metrics() -> Result<impl Reply, Rejection> {
        use prometheus::Encoder;
        let encoder = prometheus::TextEncoder::new();
        let mut buffer = Vec::new();
        let mut encode = |metrics: &[MetricFamily]| match encoder.encode(metrics, &mut buffer) {
            Ok(_) => Ok(()),
            Err(e) => Err(reject::custom(RudderReject::new(e))),
        };

        encode(&REGISTRY.gather())?;
        encode(&prometheus::gather())?;
        Ok(buffer)
    }
}
