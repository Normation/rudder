// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

// Found no other way to import log and its macros
#[macro_use]
extern crate log;

extern crate serde_json;

#[macro_use]
pub mod error;
pub mod compile;
pub mod generator;
pub mod io;
mod ir;
pub mod opt;
pub use generator::Format;
pub mod cfstrings;
pub mod logger;
mod parser;
pub mod rudderlang_lib;
pub mod technique;

use crate::technique::TechniqueFmt;

use serde::{Deserialize, Serialize};
use std::fmt;

#[derive(Clone, Copy, PartialEq, Deserialize)]
pub enum Action {
    ReadTechnique,
    GenerateTechnique,
    Migrate,
    Compile,
}
impl fmt::Display for Action {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "{}",
            match self {
                Action::ReadTechnique => "read technique",
                Action::GenerateTechnique => "generate technique",
                Action::Migrate => "migrate",
                Action::Compile => "compile",
            }
        )
    }
}
impl fmt::Debug for Action {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "{}",
            match self {
                Action::ReadTechnique => "Technique reading",
                Action::GenerateTechnique => "Technique generation",
                Action::Migrate => "Migration",
                Action::Compile => "Compilation",
            }
        )
    }
}

#[derive(Serialize)]
pub struct ActionResult {
    pub format: Format,
    pub destination: Option<String>, // None means content has directly been written in the following data field
    pub data: Option<TechniqueFmt>,  // None means content has directly been written in a file
}
impl ActionResult {
    /// ActionResult s can only hold either a destination file or directly the content
    /// If both respective fields hold a variable (or both do not), means there is a bug
    pub fn new(
        format: Format,
        technique: Option<TechniqueFmt>,
        destination: Option<String>,
    ) -> Self {
        if destination.is_none() == technique.is_none() {
            panic!("A rudderc command should always result either in a file output or a json wrapped content. Not both. Not none")
        }
        Self {
            format,
            data: technique,
            destination,
        }
    }

    // (temp)
    pub fn default() -> Self {
        Self {
            format: Format::CFEngine,
            data: None,
            destination: None,
        }
    }
}
