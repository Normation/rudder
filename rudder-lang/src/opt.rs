// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use crate::{error::Result, generator::Format, io::IOContext, output::Logs, Action};
use log::LevelFilter;
use serde::Deserialize;
use std::{cmp::PartialEq, path::PathBuf};
use structopt::StructOpt;

#[derive(Debug, StructOpt, Deserialize, PartialEq)]
#[structopt(rename_all = "kebab-case")]
/// Rudderc abilities are callable through subcommands, namely <technique read>, <technique generate>, <migrate>, <compile>,
/// allowing it to perform generation or translation from / into the following formats : [JSON, RudderLang, CFengine, DSC].
///
/// Run `rudderc <SUBCOMMAND> --help` to access its inner options and flags helper
///
/// Example:
/// rudderc technique generate -c confs/my.conf -i techniques/technique.json -f rudderlang
pub enum Opt {
    Technique(Technique),
    /// Generates a RudderLang technique from a CFEngine technique
    Migrate {
        #[structopt(flatten)]
        options: Options,

        /// Use json logs instead of human readable output
        ///
        /// This option will print a single JSON object that will contain logs, errors and generated data (or the file where it has been generated)
        ///
        /// JSON output format is always the same, whichever action is chosen.
        /// However, some fields (data and destination file) could be set to `null`, make sure to handle `null`s properly.
        ///
        /// Note that NO_COLOR specs apply by default for json output.
        ///
        /// Also note that setting NO_COLOR manually in your env will also work
        #[structopt(long, short)]
        json_logs: bool,
    },
    /// Generates either a DSC / CFEngine technique (`--format` option) from a RudderLang technique
    Compile {
        #[structopt(flatten)]
        options: Options,

        /// Use json logs instead of human readable output
        ///
        /// This option will print a single JSON object that will contain logs, errors and generated data (or the file where it has been generated)
        ///
        /// JSON output format is always the same, whichever action is chosen.
        /// However, some fields (data and destination file) could be set to `null`, make sure to handle `null`s properly.
        ///
        /// Note that NO_COLOR specs apply by default for json output.
        ///
        /// Also note that setting NO_COLOR manually in your env will also work
        #[structopt(long, short)]
        json_logs: bool,

        /// Enforce a compiler output format (overrides configuration format)
        #[structopt(long, short, possible_values = &["cf", "cfengine", "dsc"])]
        format: Option<Format>,
    },
}

/// A technique can either be used with one of the two following subcommands: `read` (from rudderlang to json) or `generate` (from json to cfengine or dsc or rudderlang)
#[derive(Debug, StructOpt, Deserialize, PartialEq)]
#[structopt(rename_all = "kebab-case")]
pub enum Technique {
    /// Generates a JSON technique from a RudderLang technique
    Read {
        #[structopt(flatten)]
        options: Options,
    },
    /// Generates a JSON object that comes with RudderLang + DSC + CFEngine technique from a JSON technique
    Generate {
        #[structopt(flatten)]
        options: Options,
    },
}

#[derive(Clone, Debug, StructOpt, Deserialize, PartialEq)]
#[structopt(rename_all = "kebab-case")]
pub struct Options {
    /// Path of the configuration file to use.
    /// A configuration file is required (containing at least stdlib and generic_methods paths)
    #[structopt(long, short, default_value = "/opt/rudder/etc/rudderc.conf")]
    pub config_file: PathBuf,

    /// Input file path.
    ///
    /// If option path does not exist, concat config input with option.
    #[structopt(long, short)]
    pub input: Option<PathBuf>,

    /// Output file path.
    ///
    /// If option path does not exist, concat config output with option.
    ///
    ///Else base output on input.
    #[structopt(long, short)]
    pub output: Option<PathBuf>,

    /// rudderc output logs verbosity.
    #[structopt(
        long,
        short,
        possible_values = &["off", "trace", "debug", "info", "warn", "error"],
        default_value = "warn"
    )]
    pub log_level: LevelFilter,

    /// Takes stdin as an input rather than using a file. Overwrites input file option
    ///
    /// Please note that the directory must exist in order to create the output.
    #[structopt(long)]
    pub stdin: bool,

    /// Takes stdout as an output rather than using a file. Overwrites output file option. Dismiss logs including errors
    #[structopt(long)]
    pub stdout: bool,

    /// Generates a backtrace in case an error occurs
    #[structopt(long, short)]
    pub backtrace: bool,
}

impl Opt {
    pub fn extract_logging_infos(&self) -> (Logs, LevelFilter, bool) {
        let (output, level, backtrace_enabled) = match self {
            Self::Compile {
                options, json_logs, ..
            } => {
                let output = match (json_logs, options.stdout) {
                    (_, true) => Logs::None,
                    (true, false) => Logs::JSON,
                    (false, false) => Logs::Terminal,
                };
                (output, options.log_level, options.backtrace)
            }
            Self::Migrate { options, json_logs } => {
                let output = match (json_logs, options.stdout) {
                    (_, true) => Logs::None,
                    (true, false) => Logs::JSON,
                    (false, false) => Logs::Terminal,
                };
                (output, options.log_level, options.backtrace)
            }
            Self::Technique(t) => t.extract_logging_infos(),
        };
        // remove log colors if JSON format to make logs vec readable
        if output == Logs::JSON {
            std::env::set_var("NO_COLOR", "1");
        }
        (output, level, backtrace_enabled)
    }

    pub fn extract_parameters(&self) -> Result<IOContext> {
        match self {
            Self::Compile {
                options, format, ..
            } => IOContext::new(self.as_action(), options, format.clone()),
            Self::Migrate { options, .. } => {
                IOContext::new(self.as_action(), options, Some(Format::RudderLang))
            }
            Self::Technique(t) => t.extract_parameters(),
        }
    }

    pub fn as_action(&self) -> Action {
        match self {
            Self::Technique(technique) => technique.as_action(),
            Self::Migrate { .. } => Action::Migrate,
            Self::Compile { .. } => Action::Compile,
        }
    }
}

impl Technique {
    fn extract_logging_infos(&self) -> (Logs, LevelFilter, bool) {
        match self {
            Self::Read { options } => {
                let output = match options.stdout {
                    true => Logs::None,
                    false => Logs::JSON,
                };
                (output, options.log_level, options.backtrace)
            }
            Self::Generate { options, .. } => {
                let output = match options.stdout {
                    true => Logs::None,
                    false => Logs::JSON,
                };
                (output, options.log_level, options.backtrace)
            }
        }
    }

    fn extract_parameters(&self) -> Result<IOContext> {
        match self {
            Self::Read { options } => IOContext::new(self.as_action(), options, Some(Format::JSON)),
            Self::Generate { options } => {
                // exception: stdin + stdout are set by default
                let mut options = options.clone();
                if options.input.is_none() {
                    options.stdin = true;
                }
                if options.output.is_none() && options.stdin {
                    options.stdout = true;
                }
                IOContext::new(self.as_action(), &options, Some(Format::JSON))
            }
        }
    }

    fn as_action(&self) -> Action {
        match self {
            Technique::Read { .. } => Action::ReadTechnique,
            Technique::Generate { .. } => Action::GenerateTechnique,
        }
    }
}
