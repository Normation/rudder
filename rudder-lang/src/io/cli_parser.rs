// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

mod options;
pub use options::Options;
mod technique;
pub use technique::Technique;

use super::output::LogOutput;
use crate::{command::Command, error::Result, generator::Format, io::IOContext, logs::LogLevel};
use serde::Deserialize;
use std::cmp::PartialEq;
use structopt::StructOpt;

#[derive(Debug, StructOpt, Deserialize, PartialEq)]
#[structopt(rename_all = "kebab-case")]
/// Rudderc abilities are callable through subcommands, namely <technique read>, <technique generate>, <save>, <compile>,
/// allowing it to perform generation or translation from / into the following formats : [JSON, RudderLang, CFengine, DSC].
///
/// Run `rudderc <SUBCOMMAND> --help` to access its inner options and flags helper
///
/// Example:
/// rudderc technique generate -c confs/my.conf -i techniques/technique.json -f rudderlang
pub enum CLI {
    Technique(Technique),
    /// Generates a RudderLang technique from a CFEngine technique
    Save {
        #[structopt(flatten)]
        options: Options,

        /// Use json logs instead of human readable output
        ///
        /// This option will print a single JSON object that will contain logs, errors and generated data (or the file where it has been generated)
        ///
        /// JSON output format is always the same, whichever command is chosen.
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
        /// JSON output format is always the same, whichever command is chosen.
        /// However, some fields (data and destination file) could be set to `null`, make sure to handle `null`s properly.
        ///
        /// Note that NO_COLOR specs apply by default for json output.
        ///
        /// Also note that setting NO_COLOR manually in your env will also work
        #[structopt(long, short)]
        json_logs: bool,

        /// Enforce a compiler output format (overrides configuration format)
        #[structopt(long, short, possible_values = &["cf", "cfengine", "dsc", "md"])]
        format: Option<Format>,
    },
}

impl CLI {
    pub fn extract_logging_infos(&self) -> (LogOutput, LogLevel, bool) {
        let (output, level, is_backtraced) = match self {
            Self::Compile {
                options, json_logs, ..
            } => {
                let output = match (json_logs, options.stdout) {
                    (_, true) => LogOutput::None,
                    (true, false) => LogOutput::JSON,
                    (false, false) => LogOutput::Raw,
                };
                (output, options.log_level, options.backtrace)
            }
            Self::Save { options, json_logs } => {
                let output = match (json_logs, options.stdout) {
                    (_, true) => LogOutput::None,
                    (true, false) => LogOutput::JSON,
                    (false, false) => LogOutput::Raw,
                };
                (output, options.log_level, options.backtrace)
            }
            Self::Technique(t) => t.extract_logging_infos(),
        };
        // remove log colors if JSON format to make logs vec readable
        if output == LogOutput::JSON {
            std::env::set_var("NO_COLOR", "1");
        }
        (output, level, is_backtraced)
    }

    pub fn extract_parameters(&self) -> Result<IOContext> {
        match self {
            Self::Compile {
                options, format, ..
            } => IOContext::new(self.as_command(), options, format.clone()),
            Self::Save { options, .. } => {
                IOContext::new(self.as_command(), options, Some(Format::RudderLang))
            }
            Self::Technique(t) => t.extract_parameters(),
        }
    }

    pub fn as_command(&self) -> Command {
        match self {
            Self::Technique(technique) => technique.as_command(),
            Self::Save { .. } => Command::Save,
            Self::Compile { .. } => Command::Compile,
        }
    }
}
