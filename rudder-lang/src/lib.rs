// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

// Found no other way to import log and its macros
#[macro_use]
extern crate log;

#[macro_use]
pub mod error;
mod ast;
pub mod compile;
pub mod file_paths;
mod generators;
pub mod logger;
mod parser;
pub mod translate;

use std::fmt;

#[derive(Clone, Copy)]
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
