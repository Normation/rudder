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
/// allowing it to perform generation or translation from / into the following formats : [JSON, Rudder, CFEngine, DSC].
///
/// Run `rudderc <SUBCOMMAND> --help` to access its inner options and flags helper
///
/// Example:
/// rudderc technique generate -c confs/my.conf -i techniques/technique.json -f Rudder
pub enum CLI {
    // See cli_parser/technique.rs for documentation
    Technique(Technique),
    /// Generates a RudderLang technique from a CFEngine technique
    Save {
        #[structopt(flatten)]
        options: Options,
    },
    /// Generates either a DSC / CFEngine technique (`--format` option) from a technique
    Compile {
        #[structopt(flatten)]
        options: Options,
        /// Enforce a compiler output format (overrides configuration format)
        #[structopt(long, short, possible_values = &["cf", "cfengine", "dsc", "md"])]
        format: Option<Format>,
    },
    /// Parses and check a technique
    Lint {
        #[structopt(flatten)]
        options: Options,
        /// Enforce a compiler output format (overrides configuration format)
        #[structopt(long, short, possible_values = &["cf", "cfengine", "dsc", "md"])]
        format: Option<Format>,
    },
}

// TODO error with stdout must be stderr
impl CLI {
    pub fn extract_logging_infos(&self) -> (LogOutput, LogLevel, bool) {
        let options = match self {
            Self::Compile { options, .. } => options,
            Self::Lint { options, .. } => options,
            Self::Save { options, .. } => options,
            Self::Technique(t) => t.get_options(),
        };
        let output = match (options.json_logs, options.stdout) {
            (_, true) => LogOutput::None,
            (true, false) => LogOutput::JSON,
            (false, false) => LogOutput::Raw,
        };
        // remove log colors if JSON format to make logs vec readable
        if output == LogOutput::JSON {
            std::env::set_var("NO_COLOR", "1");
        }
        (output, options.log_level, options.backtrace)
    }

    pub fn get_io_context(&self) -> Result<IOContext> {
        match self {
            Self::Compile {
                options, format, ..
            } => IOContext::new(self.as_command(), options, format.clone()),
            Self::Lint {
                options, format, ..
            } => IOContext::new(self.as_command(), options, format.clone()),
            Self::Save { options, .. } => {
                IOContext::new(self.as_command(), options, Some(Format::RudderLang))
            }
            Self::Technique(t) => t.get_io_context(),
        }
    }

    pub fn as_command(&self) -> Command {
        match self {
            Self::Technique(technique) => technique.as_command(),
            Self::Save { .. } => Command::Save,
            Self::Compile { .. } => Command::Compile,
            Self::Lint { .. } => Command::Lint,
        }
    }

    pub fn get_command_line(&self) -> String {
        let mut cmdline = String::new();
        for arg in std::env::args_os() {
            cmdline.push_str(&format!("{:?} ", arg));
        }
        cmdline
    }
}
