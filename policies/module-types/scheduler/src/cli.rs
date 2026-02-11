// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Normation SAS

use anyhow::Result;
use clap::Parser;
use std::path::PathBuf;

#[derive(Parser)]
#[command(version, about, long_about = None)]
pub struct Cli {
    /// JSON data file
    #[arg(short, long)]
    data: PathBuf,

    /// Output file
    #[arg(short, long)]
    out: PathBuf,

    /// Audit mode
    #[arg(short, long)]
    audit: bool,

    /// Controls output of diffs
    #[arg(short, long)]
    show_content: bool,
}

impl Cli {
    pub fn run() -> Result<()> {
        let _cli = Cli::parse();

        Ok(())
    }
}
