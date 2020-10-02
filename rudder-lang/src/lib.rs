// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

// `macro_use` attributes make related macro available to the current scope...
// but only for modules bring into scope after the statement
// so it needs to be put first
#[macro_use]
pub mod error;

#[macro_use]
pub mod io;
use io::logs;

pub mod command;
pub mod generator;
mod ir;
mod parser;
mod rudderlang_lib;
mod technique;
