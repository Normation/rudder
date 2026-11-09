// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

//! Client for the Rudder REST API, called with the caller's token

use std::{
    sync::Arc,
    time::{Duration, Instant},
};

use anyhow::{Context, Result, bail};
use axum::http::{HeaderValue, Method, StatusCode};
use secrecy::ExposeSecret;
use url::Url;

use crate::{auth::ApiToken, config::ApiConfig};

const API_TOKEN_HEADER: &str = "X-API-Token";
const API_TIMEOUT: Duration = Duration::from_secs(30);

/// Shared by all requests: holds the connection pool. Calls go through [`CallerApi`].
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
    /// stays under the API base. `query` parameters are encoded and added to the base URL's.
    fn endpoint(&self, path: &str, query: &[(&str, &str)]) -> Url {
        let mut url = Url::clone(&self.url);
        url.path_segments_mut()
            .expect("http(s) URLs have a path")
            .extend(path.split('/'));
        // Without parameters, `query_pairs_mut` would leave an empty `?`
        if !query.is_empty() {
            url.query_pairs_mut().extend_pairs(query);
        }
        url
    }

    /// The API as seen by the caller holding `token`
    pub fn for_caller(&self, token: ApiToken) -> CallerApi {
        CallerApi {
            api: self.clone(),
            token,
        }
    }
}

/// The Rudder API called with one caller's token, so Rudder applies that caller's rights. Built
/// for each request, and handed to the tools in the [`Caller`](crate::auth::Caller).
#[derive(Debug, Clone)]
pub struct CallerApi {
    api: RudderApi,
    token: ApiToken,
}

impl CallerApi {
    /// Sends a request to an endpoint (see [`RudderApi::endpoint`]) with `query` parameters (values
    /// are encoded). Errors are transport errors, the status is left to the caller.
    #[tracing::instrument(name = "rudder_api", level = "debug", skip(self))]
    pub async fn send(
        &self,
        method: Method,
        path: &str,
        query: &[(&str, &str)],
    ) -> Result<reqwest::Response> {
        let mut token = HeaderValue::from_str(self.token.0.expose_secret())
            .context("API token is not a valid header value")?;
        token.set_sensitive(true);
        let start = Instant::now();
        let response = self
            .api
            .client
            .request(method, self.api.endpoint(path, query))
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

    /// Body of a `GET` on an endpoint, with `query` parameters. A non-success status is an error, with Rudder's answer: the
    /// tools show it to the model. `right` is the Rudder right the endpoint requires (e.g.
    /// `administration_read`), named on 403 as Rudder's answer does not say it.
    pub async fn get(&self, path: &str, query: &[(&str, &str)], right: &str) -> Result<String> {
        let response = self.send(Method::GET, path, query).await?;
        let status = response.status();
        let body = response
            .text()
            .await
            .context("Rudder API response could not be read")?;
        match status {
            status if status.is_success() => Ok(body),
            // User tokens need the right, ACL tokens the path
            StatusCode::FORBIDDEN => bail!(
                "Rudder API refused GET {path} ({status}): requires the {right} right, or this path in the token's ACL: {body}"
            ),
            status => bail!("Rudder API returned {status} for GET {path}: {body}"),
        }
    }
}

#[cfg(test)]
mod tests {
    use pretty_assertions::assert_eq;

    use super::*;

    fn endpoint(server_url: &str, path: &str, query: &[(&str, &str)]) -> String {
        let config = ApiConfig {
            url: server_url.to_owned().try_into().unwrap(),
        };
        RudderApi::new(&config)
            .unwrap()
            .endpoint(path, query)
            .to_string()
    }

    #[test]
    fn endpoint_is_under_the_api_base() {
        assert_eq!(
            endpoint("https://rudder.example.com", "apiaccounts/token", &[]),
            "https://rudder.example.com/rudder/api/latest/apiaccounts/token"
        );
        assert_eq!(
            endpoint(
                "https://proxy.example.com/prefix?site=paris",
                "nodes/node 1",
                &[]
            ),
            "https://proxy.example.com/prefix/rudder/api/latest/nodes/node%201?site=paris"
        );
        assert_eq!(
            endpoint("https://rudder.example.com", "nodes/../../../etc", &[]),
            "https://rudder.example.com/rudder/api/latest/nodes/etc"
        );
    }

    #[test]
    fn endpoint_query_is_encoded() {
        assert_eq!(
            endpoint(
                "https://proxy.example.com/?site=paris",
                "nodes",
                &[
                    ("include", "default,software"),
                    ("where", r#"[{"value":"a&b=c"}]"#)
                ]
            ),
            "https://proxy.example.com/rudder/api/latest/nodes?site=paris&include=default%2Csoftware&where=%5B%7B%22value%22%3A%22a%26b%3Dc%22%7D%5D"
        );
    }
}
