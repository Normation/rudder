// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

#![allow(dead_code)]

mod archive;
mod cli;
mod config;
mod database;
mod plugin;
mod repo_index;
mod webapp_xml;

use anyhow::Result;
use clap::Parser;
use log::{debug, error, LevelFilter};

const PACKAGES_FOLDER: &str = "/var/rudder/packages";
const WEBAPP_XML_PATH: &str = "/opt/rudder/share/webapps/rudder.xml";
const PACKAGES_DATABASE_PATH: &str = "/var/rudder/packages/index.json";
const CONFIG_PATH: &str = "/opt/rudder/etc/rudder-pkg/rudder-pkg.conf";

/// CLI entry point
pub fn run() -> Result<()> {
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
    error!("This command is not implemented");
    Ok(())
}
