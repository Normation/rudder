// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

//! Output of the compiler
//!
//! The style is heavily inspired from cargo/rustc.

use std::fmt::Display;

use colored::Colorize;
use log::info;

/// Output a successful step
pub fn ok_output<T: Display>(step: &'static str, message: T) {
    info!("{:>12} {message}", step.green().bold(),);
}
