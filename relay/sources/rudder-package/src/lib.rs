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
mod webapp_xml;

use std::{path::Path, process};

use crate::cli::Command;
use anyhow::{Context, Result};
use clap::Parser;
use log::{debug, error, LevelFilter};

use crate::{config::Configuration, repository::Repository};

const PACKAGES_FOLDER: &str = "/var/rudder/packages";
const WEBAPP_XML_PATH: &str = "/opt/rudder/share/webapps/rudder.xml";
const PACKAGES_DATABASE_PATH: &str = "/var/rudder/packages/index.json";
const CONFIG_PATH: &str = "/opt/rudder/etc/rudder-pkg/rudder-pkg.conf";
const SIGNATURE_KEYRING_PATH: &str = "/opt/rudder/etc/rudder-pkg/rudder_plugins_key.gpg";
const RUDDER_VERSION_FILE: &str = "/opt/rudder/share/versions/rudder-server-version";

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
    let _repo = Repository::new(&cfg)?;
    debug!("Parsed configuration: {cfg:?}");

    match args.command {
        Command::Install { force, package } => {
            return action::install(force, package);
        }
        _ => {
            error!("This command is not implemented");
        }
    }
    Ok(())
}

pub mod action {
    use anyhow::{bail, Result};

    use crate::archive::Rpkg;
    use crate::database::Database;
    use crate::webapp_xml::restart_webapp;
    use crate::PACKAGES_DATABASE_PATH;
    use std::path::Path;

    pub fn install(force: bool, package: String) -> Result<()> {
        let rpkg_path = if Path::new(&package).exists() {
            package
        } else {
            bail!("TODO");
        };
        let rpkg = Rpkg::from_path(&rpkg_path)?;
        rpkg.install(force)?;
        restart_webapp()
    }

    pub fn list() -> Result<()> {
        let db = Database::read(PACKAGES_DATABASE_PATH);
        println!("Installed plugins:\n{:?}", db);
        Ok(())
    }
}
