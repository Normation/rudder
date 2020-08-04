// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use crate::generator::Format;
use log::LevelFilter;
use std::path::PathBuf;
use structopt::StructOpt;


#[derive(Debug, StructOpt)]
#[structopt(rename_all = "kebab-case")]
pub struct IOOpt {
    /// Path of the configuration file to use.
    /// A configuration file is required (containing at least stdlib and generic_methods paths)
    #[structopt(long, short, default_value = "/opt/rudder/etc/rudderc.conf")]
    pub config_file: PathBuf,

    /// Technique name to use for both input (if no input provided) and output (if no output provided), based on configuration file paths.
    #[structopt(long, short = "n")]
    pub technique_name: Option<PathBuf>,

    /// Technique name to use for output (if no output provided), based on configuration file paths.
    #[structopt(long, short)]
    pub output_technique_name: Option<PathBuf>,

    /// Input file path. Overwrites base input
    #[structopt(long, short)]
    pub source: Option<PathBuf>,

    /// Output file path, overwrites config and base output.
    /// If neither an output nor a configuration file default output path is set, use input
    #[structopt(long, short)]
    pub dest: Option<PathBuf>,

    /// Enforce a compiler output format (overrides configuration format)
    /// Handled formats: [ "cf" (alias "cfengine"), "dsc" ]
    #[structopt(long, short)]
    pub format: Option<Format>,
}

#[derive(Debug, StructOpt)]
#[structopt(rename_all = "kebab-case")]
pub struct Opt {
    /// Use technique translation mode rather than default compilation mode
    #[structopt(long, short)]
    pub translate: bool,

    #[structopt(flatten)]
    pub io: IOOpt,

    /// rudderc output logs verbosity.
    #[structopt(
        long,
        short,
        possible_values = &["off", "trace", "debug", "info", "warn", "error"],
        default_value = "warn"
    )]
    pub log_level: LevelFilter,

    /// Use json logs instead of human readable output
    #[structopt(long, short)]
    pub json_log: bool,

    /// Generate a backtrace in case an error occurs
    #[structopt(long, short)]
    pub backtrace: bool,
}
