// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

//! Output of the compiler
//!
//! The style is heavily inspired from cargo/rustc.

use anyhow::{bail, Error};
use colored::Colorize;
use std::{
    env,
    fmt::{Display, Formatter},
    str::FromStr, io,
};
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

    // Already handled by the colored crate by default for other output
    let no_color = env::var("NO_COLOR").is_ok();

    let builder = Subscriber::builder()
        .without_time()
        .with_target(false)
        .with_env_filter(filter);
    match format {
        OutputFormat::Human => builder.with_writer(io::stderr)
            .event_format(fmt::format().compact().without_time().with_ansi(!no_color))
            .init(),
        OutputFormat::Json => builder.event_format(fmt::format().json()).init(),
    };
}

/// Output a successful step
/// In the `cargo` style
pub fn ok_output<T: Display>(step: &'static str, message: T) {
    println!("{:>12} {message}", step.green().bold(),);
}
