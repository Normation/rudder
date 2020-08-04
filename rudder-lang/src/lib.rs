// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

// Found no other way to import log and its macros
#[macro_use]
extern crate log;

extern crate serde_json;

#[macro_use]
pub mod error;
mod ast;
pub mod io;
pub mod opt;
pub mod compile;
pub mod generator;
pub use generator::Format;
pub mod logger;
mod parser;
pub mod technique;
pub mod rudderlang_lib;
pub mod cfstrings;

use serde::Deserialize;
use std::fmt;

#[derive(Clone, Copy, PartialEq, Deserialize)]
pub enum Action {
    Compile,
    Translate,
}
impl fmt::Display for Action {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "{}",
            match self {
                Action::Compile => "compile",
                Action::Translate => "translate",
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
                Action::Compile => "Compilation",
                Action::Translate => "File translation",
            }
        )
    }
}
