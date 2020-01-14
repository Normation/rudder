// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

// Found no other way to import log and its macros
#[macro_use]
extern crate log;

#[macro_use]
pub mod error;
pub mod logger;
pub mod compile;
pub mod translate;
mod ast;
mod generators;
mod parser;
