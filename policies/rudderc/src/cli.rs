// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

use std::path::PathBuf;

use anyhow::{anyhow, Result};
use clap::Parser;

use crate::Target;

/// Compile Rudder policies
#[derive(Parser, Debug)]
#[command(author, version, about, long_about = None)]
pub struct MainArgs {
    /// File to compile
    pub input: Option<PathBuf>,

    /// Output file
    #[arg(short, long)]
    pub output: Option<PathBuf>,

    /// Output target runner
    #[arg(short, long)]
    pub target: Option<Target>,

    /// Check mode
    #[arg(short, long)]
    pub check: bool,

    /// Verbose
    #[arg(short, long, action = clap::ArgAction::Count)]
    pub verbose: u8,

    /// Quiet
    #[arg(short, long)]
    pub quiet: bool,

    /// Load a library from the given path
    #[arg(short, long, action = clap::ArgAction::Append)]
    pub library: Vec<PathBuf>,

    /// Generate a description of available resources and exit
    #[arg(long)]
    pub resource_description: bool,
}

impl MainArgs {
    /// Compute target from CLI arguments
    pub fn target(&self) -> Result<Target> {
        self.target
            .ok_or_else(|| anyhow!("No target specified"))
            // Guess from the file extension
            .or_else(|_| {
                self.output
                    .as_ref()
                    .ok_or_else(|| anyhow!("Missing output path"))
                    .and_then(|p| p.as_path().try_into())
            })
    }
}
