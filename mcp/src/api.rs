// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

//! Client for the Rudder REST API, called with the caller's token

use std::{
    sync::Arc,
    time::{Duration, Instant},
};

use anyhow::{Context, Result};
use axum::http::{HeaderValue, Method};
use secrecy::ExposeSecret;
use url::Url;

use crate::{auth::ApiToken, config::ApiConfig};

const API_TOKEN_HEADER: &str = "X-API-Token";
const API_TIMEOUT: Duration = Duration::from_secs(30);

#[derive(Debug, Clone)]
pub struct RudderApi {
    client: reqwest::Client,
    /// API base URL
    url: Arc<Url>,
}

impl RudderApi {
    pub fn new(config: &ApiConfig) -> Result<Self> {
        Ok(Self {
            client: reqwest::Client::builder()
                .timeout(API_TIMEOUT)
                // A redirect would carry the token to wherever it points: reqwest only strips
                // standard credential headers, not `X-API-Token`. The token check then fails closed.
                .redirect(reqwest::redirect::Policy::none())
                .build()
                .context("could not create the Rudder API client")?,
            url: Arc::new(config.url.api_url()),
        })
    }

    pub fn url(&self) -> &Url {
        &self.url
    }

    /// URL of an API endpoint, `path` being relative to the API base, e.g. `apiaccounts/token`.
    /// Each `/`-separated segment is percent-encoded, and `.` and `..` are dropped: the result
    /// stays under the API base.
    fn endpoint(&self, path: &str) -> Url {
        let mut url = Url::clone(&self.url);
        url.path_segments_mut()
            .expect("http(s) URLs have a path")
            .extend(path.split('/'));
        url
    }

    /// Sends a request to an endpoint (see [`Self::endpoint`]). Errors are transport errors, the status is left to
    /// the caller.
    #[tracing::instrument(name = "rudder_api", level = "debug", skip(self, token))]
    pub async fn send(
        &self,
        token: &ApiToken,
        method: Method,
        path: &str,
    ) -> Result<reqwest::Response> {
        let mut token = HeaderValue::from_str(token.0.expose_secret())
            .context("API token is not a valid header value")?;
        token.set_sensitive(true);
        let start = Instant::now();
        let response = self
            .client
            .request(method, self.endpoint(path))
            .header(API_TOKEN_HEADER, token)
            .send()
            .await
            .context("Rudder API unreachable")?;
        tracing::debug!(
            status = response.status().as_u16(),
            duration_ms = start.elapsed().as_millis(),
            "Rudder API answered"
        );
        Ok(response)
    }
}

#[cfg(test)]
mod tests {
    use pretty_assertions::assert_eq;

    use super::*;

    fn endpoint(server_url: &str, path: &str) -> String {
        let config = ApiConfig {
            url: server_url.to_owned().try_into().unwrap(),
        };
        RudderApi::new(&config).unwrap().endpoint(path).to_string()
    }

    #[test]
    fn endpoint_is_under_the_api_base() {
        assert_eq!(
            endpoint("https://rudder.example.com", "apiaccounts/token"),
            "https://rudder.example.com/rudder/api/latest/apiaccounts/token"
        );
        assert_eq!(
            endpoint(
                "https://proxy.example.com/prefix?site=paris",
                "nodes/node 1"
            ),
            "https://proxy.example.com/prefix/rudder/api/latest/nodes/node%201?site=paris"
        );
        assert_eq!(
            endpoint("https://rudder.example.com", "nodes/../../../etc"),
            "https://rudder.example.com/rudder/api/latest/nodes/etc"
        );
    }
}
