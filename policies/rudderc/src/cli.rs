// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

use std::path::PathBuf;

use clap::{Parser, Subcommand};

use crate::{doc::Format, logs::OutputFormat};

/// Compile Rudder policies
#[derive(Parser, Debug)]
#[command(author, version, about, long_about = None)]
pub struct MainArgs {
    /// Verbose
    #[arg(short, long, action = clap::ArgAction::Count)]
    pub verbose: u8,

    /// Quiet
    #[arg(short, long)]
    pub quiet: bool,

    /// Format of the output
    #[arg(long)]
    #[arg(default_value_t = OutputFormat::Json)]
    pub message_format: OutputFormat,

    /// Directory to work in, default is current directory
    #[arg(short, long)]
    pub directory: Option<PathBuf>,

    #[command(subcommand)]
    pub command: Command,
}

#[derive(Subcommand, Debug)]
pub enum Command {
    /// Create the technique structure in the current directory
    Init,

    /// Create the technique structure in the given directory
    New { name: String },

    /// Check the technique for errors
    Check {
        /// Load a library from the given path
        #[arg(short, long, action = clap::ArgAction::Append)]
        library: Vec<PathBuf>,
    },

    /// Build the technique
    Build {
        /// Load a library from the given path. Uses `/var/rudder/ncf` if not paths were provided.
        #[arg(short, long, action = clap::ArgAction::Append)]
        library: Vec<PathBuf>,

        /// Output directory
        #[arg(short, long)]
        output: Option<PathBuf>,

        /// Generate a file that can run by itself
        #[arg(long)]
        standalone: bool,

        /// Add ids to the source technique. This will also reformat the file.
        #[arg(long)]
        store_ids: bool,
    },

    /// Run the provided technique with the provided tests cases
    ///
    /// Note: It runs on the current system.
    // TODO: ability to run the test un a container or through ssh
    Test {
        /// Load a library from the given path Uses `/var/rudder/ncf` if not paths were provided.
        #[arg(short, long, action = clap::ArgAction::Append)]
        library: Vec<PathBuf>,

        /// Filter tests cases
        filter: Option<String>,

        /// Verbose agent
        #[arg(long)]
        agent_verbose: bool,
    },

    /// Build the method documentation
    Lib {
        /// Load a library from the given path Uses `/var/rudder/ncf` if not paths were provided.
        #[arg(short, long, action = clap::ArgAction::Append)]
        library: Vec<PathBuf>,

        /// Output directory
        #[arg(short, long)]
        output: Option<PathBuf>,

        /// Output format
        #[arg(short, long)]
        #[arg(value_enum)]
        #[arg(default_value_t = Format::Html)]
        format: Format,

        /// Open in browser
        #[arg(long)]
        open: bool,

        /// Output in stdout
        #[arg(long)]
        stdout: bool,
    },

    /// Remove all generated files
    Clean,
}
