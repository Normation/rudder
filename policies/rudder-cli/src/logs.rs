// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

//! Output of the compiler
//!
//! The style is heavily inspired from cargo/rustc.

use anyhow::{bail, Context, Error};
use colored::Colorize;
use std::{
    env,
    fmt::{Display, Formatter},
    path::PathBuf,
    str::FromStr,
};
use tracing::warn;
use tracing_appender::{
    non_blocking::{NonBlocking, WorkerGuard},
    rolling::RollingFileAppender,
};
use tracing_subscriber::{filter::LevelFilter, fmt, layer::SubscriberExt, EnvFilter};

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

pub fn init(
    verbose: u8,
    quiet: bool,
    format: OutputFormat,
    log_file: Option<&str>,
) -> Option<tracing_appender::non_blocking::WorkerGuard> {
    let level = match (verbose, quiet) {
        (0, true) => LevelFilter::WARN,
        (0, false) => LevelFilter::INFO,
        (1, _) => LevelFilter::DEBUG,
        (_, _) => LevelFilter::TRACE,
    };
    let global_filter = EnvFilter::builder()
        .from_env_lossy()
        .add_directive(level.into());

    // Already handled by the colored crate by default for other output
    let no_color = env::var("NO_COLOR").is_ok();
    let console_layer = if format == OutputFormat::Human {
        let human_log = tracing_subscriber::fmt::layer()
            .without_time()
            .with_target(false)
            .with_writer(std::io::stderr)
            .event_format(fmt::format().compact().without_time().with_ansi(!no_color));
        Some(human_log)
    } else {
        None
    };

    let json_layer = if format == OutputFormat::Json {
        let json_log = tracing_subscriber::fmt::layer()
            .without_time()
            .with_target(false)
            .event_format(fmt::format().json());
        Some(json_log)
    } else {
        None
    };

    // Log to file if needed
    let mut errors: Vec<String> = Vec::new();
    let (file_layer, guard) = if let Some(x) = log_file {
        match prepare_log_file(x) {
            Err(e) => {
                //e.chain().for_each(|z| errors.push(z.to_string()));
                errors.push(format!("{:?}", e));
                (None, None)
            }
            Ok((a, b)) => {
                let f = tracing_subscriber::fmt::layer()
                    .with_writer(a)
                    .event_format(fmt::format().compact().with_ansi(!no_color));
                (Some(f), Some(b))
            }
        }
    } else {
        (None, None)
    };

    let subscriber = tracing_subscriber::Registry::default().with(console_layer);
    let subscriber = subscriber
        .with(file_layer)
        .with(json_layer)
        .with(global_filter);
    tracing::subscriber::set_global_default(subscriber).expect("Unable to set global subscriber");
    if !errors.is_empty() {
        errors.iter().for_each(|e| warn!(e))
    };
    guard
}

fn prepare_log_file(log_file: &str) -> anyhow::Result<(NonBlocking, WorkerGuard)> {
    let pb = PathBuf::from(log_file);
    let directory = pb
        .parent()
        .context(format!(
            "Failed to identify the log file directory from '{}'",
            log_file
        ))?
        .display()
        .to_string();
    let filename = pb
        .file_name()
        .context(format!(
            "Failed to identify the log file name from '{}'",
            log_file
        ))?
        .to_os_string()
        .into_string();
    let a = match filename {
        Ok(x) => x,
        Err(_) => bail!(format!(
            "Failed to translate the filename to String in '{}'",
            log_file
        )),
    };
    let fa = RollingFileAppender::builder()
        .filename_prefix(a)
        .build(directory)
        .context(format!(
            "Failed to initialize rolling log file '{}'",
            log_file
        ))?;
    let (file_writer, guard) = tracing_appender::non_blocking(fa);
    Ok((file_writer, guard))
}

/// Output a successful step
/// In the `cargo` style
pub fn ok_output<T: Display>(step: &'static str, message: T) {
    println!("{:>12} {message}", step.green().bold(),);
}
