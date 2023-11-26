// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

#![allow(dead_code)]

mod archive;
mod cli;
mod cmd;
mod config;
mod database;
mod dependency;
mod list;
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

use anyhow::{anyhow, bail, Context, Result};
use clap::Parser;
use log::{debug, LevelFilter};

use crate::{
    cli::Command, config::Configuration, database::Database, list::ListOutput,
    repo_index::RepoIndex, repository::Repository, signature::SignatureVerifier,
    versions::RudderVersion, webapp::Webapp,
};

const PACKAGES_FOLDER: &str = "/var/rudder/packages";
const LICENSES_FOLDER: &str = "/opt/rudder/etc/plugins/licenses";
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

    // Read CLI args
    let args = cli::Args::parse();

    // Setup logger early
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

    // Parse configuration file
    debug!("Parsed CLI arguments: {args:?}");
    let cfg = Configuration::read(Path::new(&args.config))
        .with_context(|| format!("Reading configuration from '{}'", &args.config))?;
    debug!("Parsed configuration: {cfg:?}");

    // Now initialize all common data structures
    let verifier = SignatureVerifier::new(PathBuf::from(SIGNATURE_KEYRING_PATH));
    let repo = Repository::new(&cfg, verifier)?;
    let webapp_version = RudderVersion::from_path(RUDDER_VERSION_PATH)?;
    let mut webapp = Webapp::new(PathBuf::from(WEBAPP_XML_PATH), webapp_version);
    let mut db = Database::read(Path::new(PACKAGES_DATABASE_PATH))?;
    let index = RepoIndex::from_path(REPOSITORY_INDEX_PATH)?;

    match args.command {
        Command::Install { force, package } => package
            .into_iter()
            .try_for_each(|p| db.install(force, p, &repo,index.as_ref(), &mut webapp))?,
        Command::Uninstall { package: packages } => packages
            .into_iter()
            .try_for_each(|p| db.uninstall(&p, &mut webapp))?,
        Command::List {
            all,
            enabled,
            format,
        } => ListOutput::new(all, enabled, &db, index.as_ref(), &webapp)?.display(format)?,
        Command::Show { package } => println!(
            "{}",
            db.plugins
                .get(&package)
                .ok_or_else(|| anyhow!("Could not find plugin"))?
                .metadata
        ),
        Command::Update {} => repo.update()?,
        Command::Enable {
            package,
            all,
            save,
            restore,
        } => {
            // If all is passed, enabled all installed plugins
            let to_enable = if all {
                db.plugins.keys().cloned().collect()
            } else {
                package
            };
            if to_enable.is_empty() {
                let backup_path = Path::new(TMP_PLUGINS_FOLDER).join("plugins_status.backup");
                if save {
                    db.enabled_plugins_save(&backup_path, &mut webapp)?
                } else if restore {
                    db.enabled_plugins_restore(&backup_path, &mut webapp)?
                } else {
                    bail!("No plugin provided")
                }
            } else {
                to_enable.iter().try_for_each(|p| match db.plugins.get(p) {
                    None => bail!("Plugin {} not installed", p),
                    Some(p) => p.enable(&mut webapp),
                })?
            }
        }
        Command::Disable { package, all } => {
            let to_disable = if all {
                db.plugins.keys().cloned().collect()
            } else {
                package
            };
            to_disable
                .iter()
                .try_for_each(|p| match db.plugins.get(p) {
                    None => bail!("Plugin {} not installed", p),
                    Some(p) => p.disable(&mut webapp),
                })?
        }
        Command::CheckConnection {} => repo.test_connection()?,
    }
    // Restart if needed
    webapp.apply_changes()?;
    Ok(())
}

#[cfg(test)]
mod tests {
    #[test]
    fn i_am_not_root() {
        assert!(!super::am_i_root().unwrap())
    }
}
