// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

use std::fmt::Display;

use clap::{Parser, Subcommand, ValueEnum};

use crate::CONFIG_PATH;

#[derive(ValueEnum, Copy, Clone, Debug, Default)]
pub enum Format {
    Json,
    #[default]
    Human,
}

impl Display for Format {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "{}",
            match self {
                Self::Json => "json",
                Self::Human => "human",
            }
        )
    }
}

#[derive(Parser, Debug)]
#[command(author, version, about, long_about = None)]
pub struct Args {
    /// Configuration file path
    #[arg(short, long, default_value_t = CONFIG_PATH.into())]
    pub config: String,

    /// Enable verbose logs
    #[arg(short, long, default_value_t = false)]
    pub debug: bool,

    #[clap(subcommand)]
    pub command: Command,
}

#[derive(Debug, Subcommand)]
pub enum Command {
    /// Update package index and licenses from the repository
    Update {},
    /// Install plugins, locally or from the repository
    Install {
        #[clap(long, short, help = "Force installation of given plugin")]
        force: bool,

        #[clap()]
        package: Vec<String>,
    },
    /// Uninstall plugins
    Uninstall {
        #[clap()]
        package: Vec<String>,
    },
    /// Display the plugins list
    List {
        #[clap(long, short, help = "Show all available plugins")]
        all: bool,

        #[clap(long, short, help = "Show enabled plugins")]
        enabled: bool,

        #[clap(long, short, help = "Output format", default_value_t = Format::Human)]
        format: Format,
    },
    /// Show detailed information about a plugin
    Show {
        #[clap()]
        package: Vec<String>,
    },
    /// Enable installed plugins
    Enable {
        #[clap()]
        package: Vec<String>,

        #[clap(long, short, help = "Enable all installed plugins")]
        all: bool,

        #[clap(long, short, help = "Snapshot the list of enabled plugins")]
        save: bool,

        #[clap(
            long,
            short,
            help = "Restore the list of enabled plugins from latest snapshot"
        )]
        restore: bool,
    },
    /// Disable installed plugins
    Disable {
        #[clap()]
        package: Vec<String>,

        #[clap(long, short, help = "Enable all installed plugins")]
        all: bool,
    },
    /// Test connection to the plugin repository
    CheckConnection {},
}
