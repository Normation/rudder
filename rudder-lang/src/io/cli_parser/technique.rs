// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use super::{IOContext, LogOutput, Options};
use crate::{command::Command, error::Result, generator::Format, logs::LogLevel};
use serde::Deserialize;
use std::cmp::PartialEq;
use structopt::StructOpt;

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

impl Technique {
    pub fn extract_logging_infos(&self) -> (LogOutput, LogLevel, bool) {
        match self {
            Self::Read { options } => {
                let output = match options.stdout {
                    true => LogOutput::None,
                    false => LogOutput::JSON,
                };
                (output, options.log_level, options.backtrace)
            }
            Self::Generate { options, .. } => {
                let output = match options.stdout {
                    true => LogOutput::None,
                    false => LogOutput::JSON,
                };
                (output, options.log_level, options.backtrace)
            }
        }
    }

    pub fn extract_parameters(&self) -> Result<IOContext> {
        match self {
            Self::Read { options } => {
                // exception: stdin + stdout are set by default
                let mut options = options.clone();
                if options.input.is_none() {
                    options.stdin = true;
                }
                if options.output.is_none() && options.stdin {
                    options.stdout = true;
                }
                IOContext::new(self.as_command(), &options, Some(Format::JSON))
            }
            Self::Generate { options } => {
                // exception: stdin + stdout are set by default
                let mut options = options.clone();
                if options.input.is_none() {
                    options.stdin = true;
                }
                if options.output.is_none() && options.stdin {
                    options.stdout = true;
                }
                IOContext::new(self.as_command(), &options, Some(Format::JSON))
            }
        }
    }

    pub fn as_command(&self) -> Command {
        match self {
            Technique::Read { .. } => Command::ReadTechnique,
            Technique::Generate { .. } => Command::GenerateTechnique,
        }
    }
}
