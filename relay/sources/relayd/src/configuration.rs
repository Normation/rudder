// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

pub mod cli;
pub mod main;

use std::path::Path;

use anyhow::Error;
use main::Configuration;
use rudder_cli::logs::daemon::LogConfig;

type Warnings = Vec<Error>;

pub fn check_configuration(cfg_dir: &Path) -> Result<Warnings, Error> {
    let cfg = Configuration::new(cfg_dir)?;
    let warns = cfg.warnings();
    LogConfig::load(cfg_dir)?;
    Ok(warns)
}
