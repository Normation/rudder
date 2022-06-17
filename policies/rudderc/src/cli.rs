// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

use std::path::PathBuf;

use anyhow::{anyhow, Result};
use clap::Parser;

use crate::Target;

/// Compile Rudder policies
#[derive(Parser, Debug)]
#[clap(author, version, about, long_about = None)]
pub struct Args {
    /// File to compile
    pub input: PathBuf,

    /// Output file
    #[clap(short, long)]
    pub output: PathBuf,

    /// Output target runner
    #[clap(short, long)]
    pub target: Option<Target>,

    /// Check mode
    #[clap(short, long)]
    pub check: bool,

    /// Verbose
    #[clap(short, long, parse(from_occurrences))]
    pub verbose: usize,

    /// Quiet
    #[clap(short, long)]
    pub quiet: bool,

    /// Load library from path
    #[clap(short, long, multiple_occurrences(true))]
    pub library: Vec<PathBuf>,
}

impl Args {
    /// Compute target from CLI arguments
    pub fn target(&self) -> Result<Target> {
        self.target
            .ok_or_else(|| anyhow!("No target specified"))
            // Guess from file extension
            .or_else(|_| self.output.as_path().try_into())
    }
}
