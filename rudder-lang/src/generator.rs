// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

pub mod cfengine;
pub mod dsc;
pub mod json;
pub mod markdown;

pub use self::{cfengine::CFEngine, dsc::DSC, json::JSON, markdown::Markdown};
use crate::{error::*, ir::ir2::IR2, ActionResult};
use serde::{Deserialize, Deserializer, Serialize};
use std::{fmt, path::Path, str::FromStr};

/// A generator is something that can generate final code for a given language from an IR
/// We want at least cfengine, dsc, mgmt
pub trait Generator {
    /// If file is None: Generate code for everything that has been parsed
    /// If file is some: Only generate code for a single source file (for incremental generation or for integration with existing files)
    fn generate(
        &mut self,
        gc: &IR2,
        source_file: &str,
        dest_file: Option<&Path>,
        technique_metadata: bool,
    ) -> Result<Vec<ActionResult>>;
}

pub fn new_generator(format: &Format) -> Result<Box<dyn Generator>> {
    match format {
        Format::CFEngine => Ok(Box::new(CFEngine::new())),
        Format::DSC => Ok(Box::new(DSC::new())),
        Format::Markdown => Ok(Box::new(Markdown::new())),
        Format::JSON => Ok(Box::new(JSON)),
        _ => Err(Error::new(format!("No Generator for {} format", format))),
    }
}

#[derive(Debug, Clone, PartialEq, Serialize)]
pub enum Format {
    // rudder_lang usage is only internal to handle translation. Not a compilation format
    RudderLang,
    CFEngine,
    DSC,
    JSON,
    Markdown,
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
                Format::Markdown => "md",
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
            // RudderLang is an error, not a compilation format
            "rl" => Ok(Format::RudderLang),
            "md" => Ok(Format::Markdown),
            _ => Err(Error::new(format!("Could not parse format {}", format))),
        }
    }
}
impl<'de> Deserialize<'de> for Format {
    fn deserialize<D>(deserializer: D) -> core::result::Result<Self, D::Error>
    where
        D: Deserializer<'de>,
    {
        let strfmt = String::deserialize(deserializer)?;
        Format::from_str(&strfmt).map_err(serde::de::Error::custom)
    }
}
