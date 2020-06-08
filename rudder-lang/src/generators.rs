// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

mod cfengine;
mod dsc;

pub use self::cfengine::CFEngine;
pub use self::dsc::DSC;
use crate::ast::AST;
use crate::error::*;
use serde::de::{self, Deserialize, Deserializer};
use std::fmt;
use std::path::Path;
use std::str::FromStr;

/// A generator is something that can generate final code for a given language from an AST
/// We want at least cfengine, dsc, mgmt
pub trait Generator {
    /// If file is None: Generate code for everything that has been parsed
    /// If file is some: Only generate code for a single source file (for incremental generation or for integration with existing files)
    fn generate(
        &mut self,
        gc: &AST,
        source_file: Option<&Path>,
        dest_file: Option<&Path>,
        generic_methods: &Path,
        technique_metadata: bool,
    ) -> Result<()>;
}

pub fn new_generator(format: &Format) -> Result<Box<dyn Generator>> {
    match format {
        Format::CFEngine => Ok(Box::new(CFEngine::new())),
        Format::DSC => Ok(Box::new(DSC::new())),
        // Format::JSON => Ok(JSON::new()),
        _ => Err(Error::User(format!("No Generator for {} format", format))),
    }
}

#[derive(Debug, Clone, PartialEq)]
pub enum Format {
    // rudder_lang usage is only internal to handle translation. Not a compilation format
    RudderLang,
    CFEngine,
    DSC,
    JSON,
}
impl fmt::Display for Format {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "{}",
            match self {
                Format::CFEngine => "cf",
                Format::DSC => "ps1",
                Format::RudderLang => "rl",
                Format::JSON => "json",
            }
        )
    }
}
// Since format can only be defined by users as string, Rudder-lang does not appear to be a supported format
impl FromStr for Format {
    type Err = Error;

    fn from_str(format: &str) -> Result<Self> {
        match format {
            "cf" | "cfengine" => Ok(Format::CFEngine),
            "dsc" | "ps1" => Ok(Format::DSC),
            "json" => Ok(Format::JSON),
            "rl" => Ok(Format::RudderLang),
            // RudderLang is an error, not a compilation format
            _ => Err(Error::User(format!("Could not parse format {}", format))),
        }
    }
}
impl<'de> Deserialize<'de> for Format {
    fn deserialize<D>(deserializer: D) -> core::result::Result<Self, D::Error>
    where
        D: Deserializer<'de>,
    {
        let strfmt = String::deserialize(deserializer)?;
        Format::from_str(&strfmt).map_err(de::Error::custom)
    }
}
