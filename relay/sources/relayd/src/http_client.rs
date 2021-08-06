// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use anyhow::Error;
use lazy_static::lazy_static;
use reqwest::{Certificate, Client};
use structopt::clap::{crate_name, crate_version};
use tracing::debug;

lazy_static! {
    /// User-Agent used in our HTTP requests
    /// "rudder-relayd/7.0.0"
    static ref USER_AGENT: String = format!("{}/{}", crate_name!(), crate_version!());
}

type PemCertificate = Vec<u8>;

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

// With Rudder cert model we currently need one client for each host we talk to.
// Fortunately we only talk with other policy servers, which are only a few.
//
// Not efficient in "System" case, but it's deprecated anyway.
//
// A future improvement could be to implement public key pinning in the reqwest-hyper-tokio stack.
impl HttpClient {
    /// Common parameters
    fn new_client_builder() -> reqwest::ClientBuilder {
        Client::builder()
            // enforce HTTPS to prevent misconfigurations
            .https_only(true)
            .user_agent(USER_AGENT.clone())
    }

    // Not very efficient as we parse a cert just dumped by openssl
    pub fn new_pinned(certs: Vec<PemCertificate>) -> Result<Self, Error> {
        debug!("Creating HTTP client with pinned certificates");
        let mut client = Self::new_client_builder()
            .danger_accept_invalid_hostnames(true)
            .tls_built_in_root_certs(false);
        for cert in &certs {
            client = client.add_root_certificate(Certificate::from_pem(cert)?);
        }
        Ok(Self::Pinned(client.build()?, certs))
    }

    pub fn new_system() -> Result<Self, Error> {
        debug!("Creating HTTP client with system root certificates");
        Ok(Self::System(Self::new_client_builder().build()?))
    }

    pub fn new_no_verify() -> Result<Self, Error> {
        debug!("Creating HTTP client with no certificate verification");
        Ok(Self::System(
            Self::new_client_builder()
                .danger_accept_invalid_certs(true)
                .build()?,
        ))
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
    use super::*;
    use std::fs;

    #[test]
    fn it_creates_pinned_cert_client() {
        let cert = fs::read("tests/files/keys/37817c4d-fbf7-4850-a985-50021f4e8f41.cert").unwrap();
        let certs = vec![cert];

        let client = HttpClient::new_pinned(certs.clone()).unwrap();

        assert!(!client.outdated(&certs));

        let new_cert =
            fs::read("tests/files/keys/e745a140-40bc-4b86-b6dc-084488fc906b.cert").unwrap();
        let new_certs = vec![new_cert];

        assert!(client.outdated(&new_certs));
    }
}
