// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use crate::{error::Result, generator::Format, io::IOContext, logger::Output, Action};
use log::LevelFilter;
use std::path::PathBuf;
use structopt::StructOpt;

#[derive(Debug, StructOpt)]
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
        options: GenericOptions,

        /// Use json logs instead of human readable output
        #[structopt(long, short)]
        json_log: bool,
    },
    /// Generates either a DSC / CFEngine technique (`--format` option) from a RudderLang technique
    Compile {
        #[structopt(flatten)]
        options: GenericOptions,

        /// Use json logs instead of human readable output
        #[structopt(long, short)]
        json_log: bool,

        /// Enforce a compiler output format (overrides configuration format)
        /// Handled formats: [ "cf" (alias "cfengine"), "dsc" ]
        #[structopt(long, short, possible_values = &["cf", "cfengine", "dsc"])]
        format: Option<Format>,
    },
}
impl Opt {
    pub fn extract_logging_infos(&self) -> (Output, LevelFilter, bool) {
        match self {
            Self::Compile {
                options, json_log, ..
            } => {
                let logger = match json_log {
                    true => Output::JSON,
                    false => Output::Terminal,
                };
                (logger, options.log_level, options.backtrace)
            }
            Self::Migrate { options, json_log } => {
                let logger = match json_log {
                    true => Output::JSON,
                    false => Output::Terminal,
                };
                (logger, options.log_level, options.backtrace)
            }
            Self::Technique(t) => t.extract_logging_infos(),
        }
    }

    pub fn extract_parameters(&self) -> Result<IOContext> {
        match self {
            Self::Compile {
                options, format, ..
            } => IOContext::set(self.to_action(), options, format.clone()),
            Self::Migrate { options, .. } => IOContext::set(self.to_action(), options, None),
            Self::Technique(t) => t.extract_parameters(),
        }
    }

    pub fn to_action(&self) -> Action {
        match self {
            Self::Technique(technique) => technique.to_action(),
            Self::Migrate { .. } => Action::Migrate,
            Self::Compile { .. } => Action::Compile,
        }
    }
}

/// A technique can either be used with one of the two following subcommands: `read` (from rudderlang to json) or `generated` (from json to cfengine or dsc or rudderlang)
#[derive(Debug, StructOpt)]
#[structopt(rename_all = "kebab-case")]
pub enum Technique {
    /// Generates a JSON technique from a RudderLang technique
    Read {
        #[structopt(flatten)]
        options: GenericOptions,
    },
    /// Generates either a RudderLang / DSC / CFEngine technique (`--format` option) from a JSON technique
    Generate {
        #[structopt(flatten)]
        options: GenericOptions,
    },
}
impl Technique {
    fn extract_logging_infos(&self) -> (Output, LevelFilter, bool) {
        // might be Output::JSON instead
        match self {
            Self::Read { options } => (Output::JSON, options.log_level, options.backtrace),
            Self::Generate { options, .. } => (Output::JSON, options.log_level, options.backtrace),
        }
    }

    fn extract_parameters(&self) -> Result<IOContext> {
        match self {
            Self::Read { options } => IOContext::set(self.to_action(), options, None),
            Self::Generate { options } => IOContext::set(self.to_action(), options, None),
        }
    }

    fn to_action(&self) -> Action {
        match self {
            Technique::Read { .. } => Action::ReadTechnique,
            Technique::Generate { .. } => Action::GenerateTechnique,
        }
    }
}

#[derive(Debug, StructOpt)]
#[structopt(rename_all = "kebab-case")]
pub struct GenericOptions {
    /// Path of the configuration file to use.
    /// A configuration file is required (containing at least stdlib and generic_methods paths)
    #[structopt(long, short, default_value = "/opt/rudder/etc/rudderc.conf")]
    pub config_file: PathBuf,

    /// Input file path. Overwrites base input
    #[structopt(long, short)]
    pub input: Option<PathBuf>,

    /// Output file path, overwrites config and base output.
    /// If neither an output nor a configuration file default output path is set, base output on input
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

    /// Generate a backtrace in case an error occurs
    #[structopt(long, short)]
    pub backtrace: bool,
    // TODO add stdin + stdout
}
