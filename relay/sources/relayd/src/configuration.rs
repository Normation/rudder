// SPDX-License-Identifier: GPL-3.0-or-later WITH GPL-3.0-linking-source-exception
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

pub mod cli;
pub mod logging;
pub mod main;

use std::path::Path;

use anyhow::Error;
use logging::LogConfig;
use main::Configuration;

type Warnings = Vec<Error>;

pub fn check_configuration(cfg_dir: &Path) -> Result<Warnings, Error> {
    let cfg = Configuration::new(cfg_dir)?;
    let warns = cfg.warnings();
    LogConfig::new(cfg_dir)?;
    Ok(warns)
}
