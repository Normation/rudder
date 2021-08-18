// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

pub mod cli;
pub mod logging;
pub mod main;

use anyhow::Error;
use logging::LogConfig;
use main::Configuration;
use serde::Deserialize;
use std::{fmt, path::Path};

/// Allows hiding a value in logs
#[derive(Deserialize, PartialEq, Eq, Clone, Default)]
#[serde(transparent)]
pub struct Secret {
    value: String,
}

impl<'a> Secret {
    pub fn new(value: String) -> Self {
        Self { value }
    }

    pub fn value(&'a self) -> &'a str {
        &self.value
    }
}

impl fmt::Display for Secret {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "******")
    }
}

impl fmt::Debug for Secret {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "\"******\"")
    }
}

type Warnings = Vec<Error>;

pub fn check_configuration(cfg_dir: &Path) -> Result<Warnings, Error> {
    let cfg = Configuration::new(&cfg_dir)?;
    let warns = cfg.warnings();
    LogConfig::new(&cfg_dir)?;
    Ok(warns)
}
