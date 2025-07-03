// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

use std::{
    fs::{self, File, create_dir_all},
    path::{Path, PathBuf},
    process::ExitCode,
};

use anyhow::{Context, Result, anyhow, bail};
use reqwest::{
    Proxy, StatusCode, Url,
    blocking::{Client, Response},
};
use secrecy::ExposeSecret;
use tempfile::tempdir;
use tracing::{debug, info, warn};

use crate::license::Licenses;
use crate::{
    LICENSES_FOLDER, REPOSITORY_INDEX_PATH,
    config::{Configuration, Credentials},
    signature::{SignatureVerifier, VerificationSuccess},
    webapp::Webapp,
};

static APP_USER_AGENT: &str = concat!(env!("CARGO_PKG_NAME"), "/", env!("CARGO_PKG_VERSION"),);

#[derive(Debug)]
pub enum RepositoryError {
    InvalidCredentials(anyhow::Error),
    Unauthorized(anyhow::Error),
}
impl std::fmt::Display for RepositoryError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::InvalidCredentials(err) => write!(f, "{err}"),
            Self::Unauthorized(err) => write!(f, "{err}"),
        }
    }
}

impl std::error::Error for RepositoryError {}

impl From<&RepositoryError> for ExitCode {
    fn from(value: &RepositoryError) -> Self {
        match value {
            RepositoryError::InvalidCredentials(_) => ExitCode::from(2),
            RepositoryError::Unauthorized(_) => ExitCode::from(3),
        }
    }
}

pub struct Repository {
    inner: Client,
    creds: Option<Credentials>,
    pub server: Url,
    verifier: Box<dyn SignatureVerifier>,
}

impl Repository {
    pub fn new(config: &Configuration, verifier: Box<dyn SignatureVerifier>) -> Result<Self> {
        let mut client = Client::builder()
            .use_native_tls()
            // Enforce HTTPS at client level
            .https_only(true)
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

        // Special error messages and codes for common errors
        match res.status() {
            StatusCode::UNAUTHORIZED => {
                let e = anyhow!(
                    "Invalid credentials, please check your credentials in the configuration (received HTTP {:?}).",
                    res.status()
                );
                bail!(RepositoryError::InvalidCredentials(e))
            }
            StatusCode::FORBIDDEN | StatusCode::NOT_FOUND => {
                let e = anyhow!(
                    "Unauthorized download from Rudder repository (received HTTP {:?}).",
                    res.status()
                );
                bail!(RepositoryError::Unauthorized(e))
            }
            _ => (),
        };

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
        assert_eq!(verified, VerificationSuccess::ValidSignatureAndHash);
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
            let license_folder = Path::new(LICENSES_FOLDER);
            create_dir_all(license_folder).context("Creating the license folder")?;
            let archive_name = format!("{user}-license.tar.gz");
            let local_archive_path = &license_folder.join(&archive_name);
            if let Err(e) = self.download_unsafe(
                &format!("licenses/{user}/{archive_name}"),
                local_archive_path,
            ) {
                bail!(
                    "Could not download licenses from configured repository.\n{}",
                    e
                )
            }
            Licenses::update_from_archive(local_archive_path, license_folder)?;
        } else {
            warn!("Not updating licenses as no configured credentials were found")
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use std::{fs::read_to_string, path::PathBuf};

    use tempfile::NamedTempFile;

    use super::Repository;
    use crate::config::Configuration;
    use crate::signature::verifier;

    #[test]
    fn it_downloads_unverified_files() {
        let config = Configuration::parse("").unwrap();
        let verifier = verifier(PathBuf::from("tools/rudder_plugins_key.gpg")).unwrap();
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
        let verifier = verifier(PathBuf::from("tools/rudder_plugins_key.gpg")).unwrap();
        let repo = Repository::new(&config, verifier).unwrap();
        let dst = NamedTempFile::new().unwrap();
        repo.download(
            "8.0/consul/release/rudder-plugin-consul-8.0.3-2.1.rpkg",
            dst.path(),
        )
        .unwrap();
    }
}
