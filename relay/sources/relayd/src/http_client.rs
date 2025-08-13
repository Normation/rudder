// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use std::fs;
use std::time::Duration;

use crate::configuration::main::Configuration;
use crate::hashing::HashType::Sha256;
use crate::{CRATE_NAME, CRATE_VERSION};
use anyhow::{anyhow, Error};
use base64::Engine;
use lazy_static::lazy_static;
use openssl::x509::X509;
use reqwest::{tls::Version, Certificate, Client};
use tracing::debug;

lazy_static! {
    /// User-Agent used in our HTTP requests
    /// "rudder-relayd/7.0.0"
    static ref USER_AGENT: String = format!("{}/{}", CRATE_NAME, CRATE_VERSION);
}

pub type PemCertificate = Vec<u8>;
pub type DerCertificate = Vec<u8>;

/// Computes the public key hash from a DER certificate.
/// Uses the SHA-256 hashing algorithm to hash the public key extracted from the certificate,
/// and the curl compatible format for output.
fn public_key_hash(cert: &DerCertificate) -> Result<String, Error> {
    // Parse certificate and extract the public key.
    let pub_key = X509::from_der(cert)?.public_key()?.public_key_to_pem()?;
    let key_hash = Sha256.hash(&pub_key);
    let b64 = base64::engine::general_purpose::STANDARD;
    let encoded = b64.encode(&key_hash.value);
    Ok(format!("sha256//{}", encoded))
}

#[derive(Clone, Debug, PartialEq, Eq)]
/// All variables that can be used to configure the HTTP client.
pub struct HttpClientSettings {
    pub pubkey_pinning: bool,
    pub certificate_validation: bool,
    pub ca: Option<PemCertificate>,
    pub idle_timeout: Duration,
}

impl TryFrom<&Configuration> for HttpClientSettings {
    type Error = Error;

    fn try_from(config: &Configuration) -> Result<Self, Error> {
        let ca = match config.general.ca_path.clone() {
            Some(path) => {
                debug!("Using custom CA from {}", path.display());
                Some(fs::read(path)?)
            }
            None => None,
        };
        Ok(HttpClientSettings {
            pubkey_pinning: config.general.public_key_pinning,
            certificate_validation: config.general.certificate_validation,
            ca,
            idle_timeout: config.general.https_idle_timeout,
        })
    }
}

#[derive(Clone, Debug)]
pub struct HttpClient {
    // Stored here to be able to check if the certs are up to date.
    settings: HttpClientSettings,
    client: Client,
}

impl HttpClient {
    pub fn new(settings: HttpClientSettings) -> Result<HttpClient, Error> {
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
            .tls_info(settings.pubkey_pinning)
            .user_agent(USER_AGENT.clone())
            .pool_idle_timeout(settings.idle_timeout);

        let client = if settings.certificate_validation {
            if let Some(ca) = settings.ca.as_ref() {
                builder
                    .tls_built_in_root_certs(false)
                    .add_root_certificate(Certificate::from_pem(ca)?)
            } else {
                builder
            }
        } else {
            builder.danger_accept_invalid_certs(true)
        }
        .build()?;

        Ok(Self { settings, client })
    }

    pub async fn request(
        &self,
        req: reqwest::Request,
        pubkey_hash: Option<String>,
    ) -> Result<reqwest::Response, Error> {
        // Send the request using the client
        let response = self.client.execute(req).await?;

        if self.settings.pubkey_pinning {
            if let Some(pubkey_hash) = pubkey_hash {
                // Check if the public key matches the expected hash
                let tls_info = response.extensions().get::<reqwest::tls::TlsInfo>();
                if let Some(tls_info) = tls_info {
                    let peer_certificate = tls_info.peer_certificate();
                    if let Some(cert) = peer_certificate {
                        // Parse certificate and extract the public key.
                        let pub_key = X509::from_der(cert)?.public_key()?.public_key_to_pem()?;
                        let presenter_key_hash = Sha256.hash(&pub_key).hex();
                        if presenter_key_hash != pubkey_hash {
                            anyhow!("Public key does not match expected hash");
                        }
                    } else {
                        anyhow!("No peer certificate found in response");
                    }
                } else {
                    anyhow!("No TLS info available in response");
                }
            } else {
                anyhow!("Public key hash is required for public key pinning");
            }
        }
        Ok(response)
    }

    /// Should the client be recreated with new settings?
    pub fn outdated(&self, settings: &HttpClientSettings) -> bool {
        self.settings != *settings
    }
}

#[cfg(test)]
mod tests {
    use std::fs;

    use super::*;

    #[test]
    fn it_creates_pinned_cert_client() {
        let cert = fs::read("tests/files/keys/37817c4d-fbf7-4850-a985-50021f4e8f41.cert").unwrap();

        let settings = HttpClientSettings {
            pubkey_pinning: true,
            certificate_validation: true,
            ca: Some(cert.clone()),
            idle_timeout: Duration::from_secs(10),
        };

        let client = HttpClient::new(settings.clone()).unwrap();

        assert!(!client.outdated(&settings));

        let new_cert =
            fs::read("tests/files/keys/e745a140-40bc-4b86-b6dc-084488fc906b.cert").unwrap();
        let new_settings = HttpClientSettings {
            ca: Some(new_cert),
            ..settings
        };

        assert!(client.outdated(&new_settings));
    }

    #[test]
    fn it_computes_public_key_hash() {
        let pem_cert = fs::read("tests/files/http/cert.pem").unwrap();
        let der_cert = X509::from_pem(&pem_cert).unwrap().to_der().unwrap();
        let expected_hash = "sha256//4BmcSBb6WJebDT5p0Y6yKHvmlyh193YN5no9Pj6D5Vo=";

        let hash = public_key_hash(&der_cert).unwrap();
        assert_eq!(hash, expected_hash);
    }
}
