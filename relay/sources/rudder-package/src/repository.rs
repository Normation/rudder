// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

use std::{
    fs::{self, File},
    path::{Path, PathBuf},
};

use anyhow::{anyhow, bail, Context, Result};
use flate2::read::GzDecoder;
use log::{debug, info};
use reqwest::{
    blocking::{Client, Response},
    Proxy, StatusCode, Url,
};
use tar::Archive;
use tempfile::tempdir;

use crate::{
    config::{Configuration, Credentials},
    signature::{SignatureVerifier, VerificationSuccess},
    webapp::Webapp,
    REPOSITORY_INDEX_PATH,
};

static APP_USER_AGENT: &str = concat!(env!("CARGO_PKG_NAME"), "/", env!("CARGO_PKG_VERSION"),);

#[derive(Clone)]
pub struct Repository {
    inner: Client,
    creds: Option<Credentials>,
    server: Url,
    verifier: SignatureVerifier,
}

impl Repository {
    pub fn new(config: &Configuration, verifier: SignatureVerifier) -> Result<Self> {
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
        // We need to ensure URL ends with a slash
        let url = if config.url.ends_with("plugins") {
            let mut u = config.url.to_owned();
            u.push('/');
            u
        } else {
            config.url.to_owned()
        };
        let server = Url::parse(&url)?;

        Ok(Self {
            inner: client.build()?,
            creds: config.credentials.clone(),
            server,
            verifier,
        })
    }

    pub fn get_username(&self) -> Option<String> {
        self.creds.as_ref().map(|c| c.username.clone())
    }

    fn get(&self, path: &str) -> Result<Response> {
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

    // Path is relative to the confiured server
    pub fn download_unsafe(&self, file: &str, dest: &Path) -> Result<()> {
        debug!(
            "Downloading file from {} to '{}'",
            self.server.join(file)?,
            dest.display().to_string()
        );
        let mut res = self.get(file)?;
        let mut file = File::create(dest)?;
        res.copy_to(&mut file)?;
        Ok(())
    }

    /// Downloads a file and validate its signature
    ///
    /// The `path` is relative to the configured server
    pub fn download(&self, file: &str, dest: &Path) -> Result<()> {
        let path = Path::new(file);
        let plugin_file = path
            .file_name()
            .ok_or_else(|| anyhow!("Path to download '{}' should contain a file name", file))?;
        let hash_file = path
            .parent()
            .unwrap()
            .join("SHA512SUMS")
            .to_string_lossy()
            .into_owned();
        let sign_file = path
            .parent()
            .unwrap()
            .join("SHA512SUMS.asc")
            .to_string_lossy()
            .into_owned();

        let tmp = tempdir()?;
        let tmp = tmp.path();

        let tmp_plugin = tmp.join(plugin_file);
        let tmp_hash = tmp.join("SHA512SUMS");
        let tmp_sign = tmp.join("SHA512SUMS.asc");

        self.download_unsafe(file, &tmp_plugin)?;
        self.download_unsafe(hash_file.as_ref(), &tmp_hash)?;
        self.download_unsafe(sign_file.as_ref(), &tmp_sign)?;

        let verified = self
            .verifier
            .verify_file(&tmp_plugin, &tmp_sign, &tmp_hash)?;
        assert!(verified == VerificationSuccess::ValidSignatureAndHash);
        fs::copy(tmp_plugin, dest)?;
        Ok(())
    }

    pub fn test_connection(&self) -> Result<()> {
        self.get("")
            .context(format!("Could not connect with {}", self.server))?;
        info!("Connection with {}: OK", self.server);
        Ok(())
    }

    /// Update index and licences
    pub fn update(&self, webapp: &Webapp) -> Result<()> {
        // Update the index file
        debug!("Updating repository index");
        let rudder_version = &webapp.version;
        let remote_index = format!(
            "{}.{}/rpkg.index",
            rudder_version.major, rudder_version.minor
        );
        self.download_unsafe(&remote_index, &PathBuf::from(REPOSITORY_INDEX_PATH))?;

        // Update the licenses
        if let Some(user) = self.get_username() {
            debug!("Updating licenses");
            // FIXME: take as param, we need to be able to test this
            let license_folder = PathBuf::from("/opt/rudder/etc/plugins/licenses");
            let archive_name = format!("{}-license.tar.gz", user);
            let local_archive_path = &license_folder.clone().join(archive_name.clone());
            if let Err(e) = self.download_unsafe(
                &format!("licenses/{}/{}", user, archive_name),
                local_archive_path,
            ) {
                bail!(
                    "Could not download licenses from configured repository.\n{}",
                    e
                )
            }
            // Decompress archive
            let mut archive = Archive::new(GzDecoder::new(File::open(local_archive_path)?));
            archive.unpack(license_folder)?;
        } else {
            debug!("Not updating licenses as no configured credentials were found")
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use std::{fs::read_to_string, path::PathBuf};

    use tempfile::NamedTempFile;

    use super::Repository;
    use crate::{config::Configuration, signature::SignatureVerifier};

    #[test]
    fn it_downloads_unverified_files() {
        let config = Configuration::parse("").unwrap();
        let verifier = SignatureVerifier::new(PathBuf::from("tools/rudder_plugins_key.gpg"));
        let repo = Repository::new(&config, verifier).unwrap();
        let dst = NamedTempFile::new().unwrap();
        repo.download_unsafe("../rpm/rudder_rpm_key.pub", dst.path())
            .unwrap();
        let contents = read_to_string(dst.path()).unwrap();
        assert!(contents.starts_with("-----BEGIN PGP PUBLIC KEY BLOCK----"))
    }

    #[test]
    fn it_downloads_verified_files() {
        let config = Configuration::parse("").unwrap();
        let verifier = SignatureVerifier::new(PathBuf::from("tools/rudder_plugins_key.gpg"));
        let repo = Repository::new(&config, verifier).unwrap();
        let dst = NamedTempFile::new().unwrap();
        repo.download(
            "8.0/consul/release/rudder-plugin-consul-8.0.3-2.1.rpkg",
            dst.path(),
        )
        .unwrap();
    }
}
