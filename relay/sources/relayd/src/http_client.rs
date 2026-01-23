// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use std::time::Duration;

use anyhow::Error;
use lazy_static::lazy_static;
use reqwest::{tls::Version, Certificate, Client};
use tracing::debug;

use crate::{CRATE_NAME, CRATE_VERSION};

lazy_static! {
    /// User-Agent used in our HTTP requests
    /// "rudder-relayd/7.0.0"
    static ref USER_AGENT: String = format!("{}/{}", CRATE_NAME, CRATE_VERSION);
}

pub type PemCertificate = Vec<u8>;

#[derive(Clone, Debug)]
pub enum HttpClient {
    /// Keep the associated certificates to be able to compare afterwards
    ///
    /// We can't currently compare reqwest::Certificate or openssl::X509Ref,
    /// so we'll compare pem exports.
    Pinned(Client, Vec<PemCertificate>),
    /// For compatibility for 6.X
    System(Client),
    NoVerify(Client),
}

pub struct HttpClientBuilder {
    builder: reqwest::ClientBuilder,
}

// With Rudder cert model we currently need one client for each host we talk to.
// Fortunately we only talk with other policy servers, which are only a few.
//
// Not efficient in "System" case, but it's deprecated anyway.
//
// A future improvement could be to implement public key pinning in the reqwest-hyper-tokio stack.
impl HttpClientBuilder {
    /// Common parameters
    pub fn new(idle_timeout: Duration) -> Self {
        let builder = Client::builder()
            // enforce HTTPS to prevent misconfigurations
            .https_only(true)
            // Not possible to enforce 1.3 for now with native-tls:
            // https://docs.rs/reqwest/0.11.18/reqwest/struct.ClientBuilder.html#errors-1
            // https://github.com/sfackler/rust-native-tls/pull/235
            //
            // And we can't switch to rustls due to missing:
            // https://docs.rs/reqwest/0.11.18/reqwest/struct.ClientBuilder.html#method.danger_accept_invalid_hostnames
            .min_tls_version(Version::TLS_1_2)
            .user_agent(USER_AGENT.clone())
            .pool_idle_timeout(idle_timeout);
        Self { builder }
    }

    // Not very efficient as we parse a cert just dumped by openssl
    pub fn pinned(self, certs: Vec<PemCertificate>) -> Result<HttpClient, Error> {
        debug!("Creating HTTP client with pinned certificates");
        let parsed_certs = certs
            .iter()
            .map(|c| Certificate::from_pem(c))
            .collect::<Result<Vec<Certificate>, _>>()?;
        let client = self
            .builder
            .danger_accept_invalid_hostnames(true)
            .tls_certs_only(parsed_certs);
        Ok(HttpClient::Pinned(client.build()?, certs))
    }

    pub fn system(self) -> Result<HttpClient, Error> {
        debug!("Creating HTTP client with system root certificates");
        Ok(HttpClient::System(self.builder.build()?))
    }

    pub fn custom_ca(self, ca: &PemCertificate) -> Result<HttpClient, Error> {
        debug!("Creating HTTP client with custom CA");
        let mut client = self.builder;
        client = client.add_root_certificate(Certificate::from_pem(ca)?);
        Ok(HttpClient::System(client.build()?))
    }

    pub fn no_verify(self) -> Result<HttpClient, Error> {
        debug!("Creating HTTP client with no certificate verification");
        Ok(HttpClient::System(
            self.builder.danger_accept_invalid_certs(true).build()?,
        ))
    }
}

// With Rudder cert model we currently need one client for each host we talk to.
// Fortunately we only talk with other policy servers, which are only a few.
//
// Not efficient in "System" case, but it's deprecated anyway.
//
// A future improvement could be to implement public key pinning in the reqwest-hyper-tokio stack.
impl HttpClient {
    /// Common parameters
    pub fn builder(idle_timeout: Duration) -> HttpClientBuilder {
        HttpClientBuilder::new(idle_timeout)
    }

    /// Access inner client
    pub fn inner(&self) -> &Client {
        match *self {
            Self::Pinned(ref c, _) => c,
            Self::System(ref c) => c,
            Self::NoVerify(ref c) => c,
        }
    }

    /// If order is not good, reload
    /// We have only one certificate anyway
    pub fn outdated(&self, certs: &[PemCertificate]) -> bool {
        match *self {
            Self::Pinned(_, ref current) => current.as_slice() != certs,
            _ => unreachable!("Reload is only possible for cert-pinning based-clients"),
        }
    }
}

#[cfg(test)]
mod tests {
    use std::fs;

    use super::*;

    #[test]
    fn it_creates_pinned_cert_client() {
        let cert = fs::read("tests/files/keys/37817c4d-fbf7-4850-a985-50021f4e8f41.cert").unwrap();
        let certs = vec![cert];

        let client = HttpClient::builder(Duration::from_secs(10))
            .pinned(certs.clone())
            .unwrap();

        assert!(!client.outdated(&certs));

        let new_cert =
            fs::read("tests/files/keys/e745a140-40bc-4b86-b6dc-084488fc906b.cert").unwrap();
        let new_certs = vec![new_cert];

        assert!(client.outdated(&new_certs));
    }
}
