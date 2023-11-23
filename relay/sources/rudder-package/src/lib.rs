// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

#![allow(dead_code)]

mod archive;
mod cli;
mod cmd;
mod config;
mod database;
mod dependency;
mod plugin;
mod repo_index;
mod repository;
mod signature;
mod versions;
mod webapp;

use std::{
    path::{Path, PathBuf},
    process,
};

use crate::{cli::Command, signature::SignatureVerifier};
use anyhow::{Context, Result};
use clap::Parser;
use log::{debug, error, LevelFilter};

use crate::{config::Configuration, repository::Repository};

const PACKAGES_FOLDER: &str = "/var/rudder/packages";
const WEBAPP_XML_PATH: &str = "/opt/rudder/share/webapps/rudder.xml";
const PACKAGES_DATABASE_PATH: &str = "/var/rudder/packages/index.json";
const CONFIG_PATH: &str = "/opt/rudder/etc/rudder-pkg/rudder-pkg.conf";
const SIGNATURE_KEYRING_PATH: &str = "/opt/rudder/etc/rudder-pkg/rudder_plugins_key.gpg";
const RUDDER_VERSION_PATH: &str = "/opt/rudder/share/versions/rudder-server-version";
const REPOSITORY_INDEX_PATH: &str = "/var/rudder/tmp/plugins/rpkg.index";
const TMP_PLUGINS_FOLDER: &str = "/var/rudder/tmp/plugins";

fn am_i_root() -> Result<bool> {
    let out = process::Command::new("id").arg("--user").output()?;
    let uid = String::from_utf8_lossy(&out.stdout)
        .strip_suffix('\n')
        .unwrap()
        .parse::<usize>()?;
    Ok(uid == 0)
}

/// CLI entry point
pub fn run() -> Result<()> {
    // Abort of not run as root
    // Ignore on error
    #[cfg(not(debug_assertions))]
    if let Ok(false) = am_i_root() {
        eprintln!("This program needs to run as root, aborting.");
        process::exit(1);
    }

    let args = cli::Args::parse();
    let filter = if args.debug {
        LevelFilter::Debug
    } else {
        LevelFilter::Info
    };
    env_logger::builder()
        .format_timestamp(None)
        .format_module_path(false)
        .format_target(false)
        .filter_level(filter)
        .init();
    debug!("Parsed CLI arguments: {args:?}");
    let cfg = Configuration::read(Path::new(&args.config))
        .with_context(|| format!("Reading configuration from '{}'", &args.config))?;
    let verifier = SignatureVerifier::new(PathBuf::from(SIGNATURE_KEYRING_PATH));
    let repo = Repository::new(&cfg, verifier)?;
    debug!("Parsed configuration: {cfg:?}");

    match args.command {
        Command::Install { force, package } => {
            return action::install(force, package, repo);
        }
        Command::Uninstall { package } => {
            return action::uninstall(package);
        }
        _ => {
            error!("This command is not implemented");
        }
    }
    Ok(())
}

pub mod action {
    use anyhow::{anyhow, bail, Result};
    use log::debug;

    use crate::archive::Rpkg;
    use crate::database::Database;
    use crate::repo_index::RepoIndex;
    use crate::repository::Repository;
    use crate::versions::RudderVersion;
    use crate::webapp::Webapp;
    use crate::{
        PACKAGES_DATABASE_PATH, REPOSITORY_INDEX_PATH, RUDDER_VERSION_PATH, TMP_PLUGINS_FOLDER, WEBAPP_XML_PATH,
    };
    use std::path::{Path, PathBuf};

    pub fn uninstall(packages: Vec<String>) -> Result<()> {
        let mut db = Database::read(PACKAGES_DATABASE_PATH)?;
        packages.iter().try_for_each(|p| db.uninstall(p))
    }

    pub fn install(force: bool, packages: Vec<String>, repository: Repository) -> Result<()> {
        packages.iter().try_for_each(|package| {
            let rpkg_path = if Path::new(&package).exists() {
                package.clone()
            } else {
                // Find compatible plugin if any
                let webapp_version = RudderVersion::from_path(RUDDER_VERSION_PATH)?;
                let index = RepoIndex::from_path(REPOSITORY_INDEX_PATH)?;
                let to_dl_and_install = match index.get_compatible_plugin(webapp_version, package) {
                    None => bail!("Could not find any compatible '{}' plugin with the current Rudder version in the configured repository.", package),
                    Some(p) => {
                        debug!("Found a compatible plugin in the repository:\n{:?}", p);
                        p
                    }
                };
                let dest = Path::new(TMP_PLUGINS_FOLDER).join(
                    Path::new(&to_dl_and_install.path)
                        .file_name()
                        .ok_or_else(|| {
                            anyhow!(
                                "Could not retrieve filename from path '{}'",
                                to_dl_and_install.path
                            )
                        })?,
                );
                // Download rpkg
                repository.clone().download(&to_dl_and_install.path, &dest)?;
                dest.as_path().display().to_string()
            };
            let rpkg = Rpkg::from_path(&rpkg_path)?;
            rpkg.install(force)?;
            Ok(())
        })?;
        // FIXME only one!
        let mut webapp = Webapp::new(PathBuf::from(WEBAPP_XML_PATH));
        webapp.apply_changes()
    }

    pub fn list() -> Result<()> {
        let db = Database::read(PACKAGES_DATABASE_PATH);
        println!("Installed plugins:\n{:?}", db);
        Ok(())
    }
}
