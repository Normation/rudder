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

    /// Disable non-error logs
    #[arg(short, long, default_value_t = false)]
    pub quiet: bool,

    #[command(subcommand)]
    pub command: Command,
}

#[derive(Debug, Subcommand)]
pub enum Command {
    /// Update package index and licenses from the repository
    Update {
        #[arg(long, short, help = "Only check for server connectivity and exit")]
        check: bool,

        #[arg(
            long,
            short,
            help = "Exit without error if the repository is not reachable"
        )]
        if_available: bool,
    },
    /// Install plugins, locally or from the repository
    Install {
        #[arg(
            long,
            short,
            help = "Bypass compatibility check and force installation of the given plugin version"
        )]
        force: bool,

        #[arg(
            help = "Plugin name(s) or file path(s). You can specify a precise version with 'name:version' (like 'cve:8.1.0-1.2')",
            required = true
        )]
        package: Vec<String>,
    },
    /// Upgrade plugins
    Upgrade {
        #[arg(long, short, help = "Upgrade all installed plugins")]
        all: bool,

        #[arg(long, help = "Run all the postinstall scripts of installed plugins")]
        all_postinstall: bool,

        #[arg()]
        package: Vec<String>,
    },
    /// Uninstall plugins
    #[command(visible_alias = "remove")]
    Uninstall {
        #[arg(help = "Plugin name(s)", required = true)]
        package: Vec<String>,
    },
    /// Display the plugins list
    List {
        #[arg(long, short, help = "Show all installed and available plugins")]
        all: bool,

        #[arg(long, short, help = "Show enabled plugins")]
        enabled: bool,

        #[arg(long, short, help = "Output format", default_value_t = Format::Human)]
        format: Format,
    },
    /// Show detailed information about a plugin
    Show {
        #[arg(help = "Plugin name(s)", required = true)]
        package: Vec<String>,
    },
    /// Show general information about plugin management
    Info {},
    /// Enable installed plugins
    Enable {
        #[arg(help = "Plugin name(s)")]
        package: Vec<String>,

        #[arg(long, short, help = "Enable all installed plugins")]
        all: bool,

        #[arg(long, short, help = "Snapshot the list of enabled plugins")]
        save: bool,

        #[arg(
            long,
            short,
            help = "Restore the list of enabled plugins from latest snapshot"
        )]
        restore: bool,
    },
    /// Disable installed plugins
    Disable {
        #[arg(help = "Plugin name(s)")]
        package: Vec<String>,

        #[arg(long, short, help = "Disable all installed plugins")]
        all: bool,

        #[arg(
            long,
            short,
            help = "Disable all installed plugins incompatible with the Web application version"
        )]
        incompatible: bool,
    },
}
