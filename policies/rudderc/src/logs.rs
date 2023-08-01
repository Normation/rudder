// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

//! Output of the compiler
//!
//! The style is heavily inspired from cargo/rustc.

use std::{
    fmt::{Display, Formatter},
    str::FromStr,
};

use anyhow::{bail, Error};
use tracing_subscriber::{filter::LevelFilter, fmt, fmt::Subscriber, EnvFilter};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum OutputFormat {
    #[default]
    Human,
    Json,
}

impl Display for OutputFormat {
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "{}",
            match self {
                Self::Human => "human",
                Self::Json => "json",
            }
        )
    }
}

impl FromStr for OutputFormat {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        Ok(match s.to_lowercase().as_str() {
            "json" => Self::Json,
            "human" => Self::Human,
            _ => bail!("Unrecognized output format '{s}'"),
        })
    }
}

pub fn init(verbose: u8, quiet: bool, format: OutputFormat) {
    let level = match (verbose, quiet) {
        (0, true) => LevelFilter::WARN,
        (0, false) => LevelFilter::INFO,
        (1, _) => LevelFilter::DEBUG,
        (_, _) => LevelFilter::TRACE,
    };
    let filter = EnvFilter::builder()
        .from_env_lossy()
        .add_directive(level.into());

    let builder = Subscriber::builder().without_time().with_env_filter(filter);
    match format {
        OutputFormat::Human => builder.event_format(fmt::format().compact()).init(),
        OutputFormat::Json => builder.event_format(fmt::format().json()).init(),
    };
}
