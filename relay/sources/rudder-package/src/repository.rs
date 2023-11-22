// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

use std::{fs::File, path::Path};

use anyhow::{bail, Result};
use log::debug;
use reqwest::{
    blocking::{Client, Response},
    Proxy, StatusCode, Url,
};

use crate::config::{Configuration, Credentials};

static APP_USER_AGENT: &str = concat!(env!("CARGO_PKG_NAME"), "/", env!("CARGO_PKG_VERSION"),);

pub struct Repository {
    inner: Client,
    creds: Option<Credentials>,
    server: Url,
}

impl Repository {
    pub fn new(config: &Configuration) -> Result<Self> {
        let mut client = Client::builder()
            .use_native_tls()
            .user_agent(APP_USER_AGENT);

        if let Some(proxy_cfg) = &config.proxy {
            let mut proxy = Proxy::all(proxy_cfg.url.clone())?;
            if let Some(creds) = &proxy_cfg.credentials {
                // FIXME support digest?
                proxy = proxy.basic_auth(&creds.username, creds.password.expose_secret());
            }
            client = client.proxy(proxy)
        }
        let server = Url::parse(&config.url)?;

        Ok(Self {
            inner: client.build()?,
            creds: config.credentials.clone(),
            server,
        })
    }

    fn get(self, path: &str) -> Result<Response> {
        let mut req = self.inner.get(self.server.join(path)?);
        if let Some(creds) = &self.creds {
            req = req.basic_auth(&creds.username, Some(creds.password.expose_secret()))
        }
        let res = req.send()?;

        // Special error messages for common errors
        match res.status() {
            StatusCode::UNAUTHORIZED => bail!("Received an HTTP 401 Unauthorized error when trying to get {}. Please check your credentials in the configuration.", path),
            StatusCode::FORBIDDEN => bail!("Received an HTTP 403 Forbidden error when trying to get {}. Please check your credentials in the configuration.", path),
            StatusCode::NOT_FOUND => bail!("Received an HTTP 404 Not found error when trying to get {}. Please check your configuration.", path),
            _ => ()
        }

        Ok(res.error_for_status()?)
    }

    // Path is relative to the server
    pub fn download(self, path: &str, dest: &Path) -> Result<()> {
        debug!(
            "Downloading file from {} to '{}'",
            self.server.join(path)?,
            dest.display().to_string()
        );
        let mut res = self.get(path)?;
        let mut file = File::create(dest)?;
        res.copy_to(&mut file)?;
        Ok(())
    }

    pub fn test_connection(self) -> Result<()> {
        self.get("")?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use std::fs::read_to_string;

    use tempfile::NamedTempFile;

    use super::Repository;
    use crate::config::Configuration;

    #[test]
    fn it_downloads_files() {
        let config = Configuration::parse("").unwrap();
        let repo = Repository::new(&config).unwrap();
        let dst = NamedTempFile::new().unwrap();
        repo.download("rpm/rudder_rpm_key.pub", dst.path()).unwrap();
        let contents = read_to_string(dst.path()).unwrap();
        assert!(contents.starts_with("-----BEGIN PGP PUBLIC KEY BLOCK----"))
    }
}
