// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

//! Target != Backend, we could have different target compiled by the same backend

use std::{ffi::OsStr, fmt, path::Path, str::FromStr};

use anyhow::{anyhow, bail, Error, Result};
use serde::{Deserialize, Deserializer, Serialize};

pub use self::{unix::Unix, windows::Windows};
use crate::ir::Policy;

mod metadata;
pub mod unix;
pub mod windows;

/// A backend is something that can generate final code for a given language from an IR
pub trait Backend {
    // For now, we only generate one file content
    fn generate(&self, policy: Policy) -> Result<String>;
}

#[derive(Debug, Copy, Clone, PartialEq, Serialize)]
pub enum Target {
    /// It is not actually CFEngine but CFEngine + our patches + our stdlib
    ///
    /// It matches what is called "classic agent" in Rudder.
    Unix,
    Windows,
}

impl Target {
    /// Extension of the output files
    pub fn extension(self) -> &'static str {
        match self {
            Self::Unix => "cf",
            Self::Windows => "ps1",
        }
    }

    /// Select the right backend
    pub fn backend(self) -> Box<dyn Backend> {
        match self {
            Target::Unix => Box::new(Unix::new()),
            Target::Windows => Box::new(Windows::new()),
        }
    }
}

/// Detect target from file extension
impl TryFrom<&Path> for Target {
    type Error = Error;

    fn try_from(file: &Path) -> core::result::Result<Self, Self::Error> {
        let extension = file
            .extension()
            .and_then(OsStr::to_str)
            .ok_or_else(|| anyhow!("Could not read file extension as UTF-8"))?;

        extension.parse()
    }
}

// standard target name
impl fmt::Display for Target {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "{}",
            match self {
                Target::Unix => "unix",
                Target::Windows => "windows",
            }
        )
    }
}

impl FromStr for Target {
    type Err = Error;

    fn from_str(target: &str) -> Result<Self> {
        match target {
            "cf" | "cfengine" | "CFEngine" | "unix" | "Unix" => Ok(Target::Unix),
            "ps1" | "powershell" | "dsc" | "windows" | "Windows" => Ok(Target::Windows),
            "yml" | "yaml" => bail!("YAML is not a valid target format but only a source"),
            _ => bail!("Could not recognize target {:?}", target),
        }
    }
}

impl<'de> Deserialize<'de> for Target {
    fn deserialize<D>(deserializer: D) -> core::result::Result<Self, D::Error>
    where
        D: Deserializer<'de>,
    {
        let fmt = String::deserialize(deserializer)?;
        Target::from_str(&fmt).map_err(serde::de::Error::custom)
    }
}
