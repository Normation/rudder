// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

//! Output of the compiler
//!
//! The style is heavily inspired from cargo/rustc.

use anyhow::{Context, Error, Result, bail};
use colored::Colorize;
use std::{
    env,
    fmt::{Display, Formatter},
    io,
    path::Path,
    str::FromStr,
};
use tracing_appender::rolling::RollingFileAppender;
use tracing_subscriber::layer::SubscriberExt;
use tracing_subscriber::{EnvFilter, Layer, filter::LevelFilter, fmt};

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

/// `file_log` is a `(log_dir, file_name)` tuple
pub fn init(
    verbose: u8,
    quiet: bool,
    format: OutputFormat,
    file_log: Option<(&Path, &str)>,
) -> Result<()> {
    let level = match (verbose, quiet) {
        (0, true) => LevelFilter::WARN,
        (0, false) => LevelFilter::INFO,
        (1, _) => LevelFilter::DEBUG,
        (_, _) => LevelFilter::TRACE,
    };
    // Already handled by the colored crate by default for other output
    let no_color = env::var("NO_COLOR").is_ok();

    // Formatters
    let stderr_fmt = fmt::format()
        .compact()
        .without_time()
        .with_target(false)
        .with_ansi(!no_color);
    let logfile_fmt = fmt::format().compact().with_target(false).with_ansi(false);
    let json_fmt = fmt::format().without_time().with_target(false).json();

    // Layers
    let human = tracing_subscriber::fmt::layer()
        .event_format(stderr_fmt)
        .with_writer(io::stderr)
        .with_filter(
            EnvFilter::builder()
                .from_env_lossy()
                .add_directive(level.into()),
        );

    let file = if let Some((log_dir, log_file_prefix)) = file_log {
        let file_writer = RollingFileAppender::builder()
            .filename_prefix(log_file_prefix)
            // .log extension
            .filename_suffix("log")
            .build(log_dir)
            .context(format!(
                "Failed to initialize rolling log file in '{}'",
                log_dir.display()
            ))?;
        Some(
            tracing_subscriber::fmt::layer()
                .event_format(logfile_fmt)
                .with_writer(file_writer)
                .with_filter(
                    EnvFilter::builder()
                        .from_env_lossy()
                        .add_directive(level.into()),
                ),
        )
    } else {
        None
    };

    let json = if format == OutputFormat::Json {
        Some(
            tracing_subscriber::fmt::layer()
                .event_format(json_fmt)
                .with_filter(
                    EnvFilter::builder()
                        .from_env_lossy()
                        .add_directive(level.into()),
                ),
        )
    } else {
        None
    };

    // Now build the registry
    let logger = tracing_subscriber::registry()
        .with(human)
        .with(json)
        .with(file);
    tracing::subscriber::set_global_default(logger)?;
    Ok(())
}

/// Output a successful step
/// In the `cargo` style
pub fn ok_output<T: Display>(step: &'static str, message: T) {
    println!("{:>12} {message}", step.green().bold(),);
}
