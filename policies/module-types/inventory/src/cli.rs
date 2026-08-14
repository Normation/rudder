// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

use std::path::PathBuf;

use clap::Parser;

/// Collect a Rudder inventory
#[derive(Parser, Debug)]
#[command(author, version, about, long_about = None)]
pub struct Cli {
    /// Write an inventory in the given FILE
    #[arg(short, long, value_name = "FILE")]
    pub local: PathBuf,

    /// Turn debugging information on, twice for tracing
    #[arg(short, long, action = clap::ArgAction::Count)]
    pub debug: u8,

    /// Only report warnings and errors
    #[arg(short, long)]
    pub quiet: bool,
}
