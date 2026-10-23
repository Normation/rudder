// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

//! Logs of the daemons (relayd, rudder-mcp), configured in a `logging.conf` file
//!
//! They log to stdout, read by the journal, which adds timestamps.

use std::{fmt, path::Path, str::FromStr};

use anyhow::{Context, Error, Result};
use serde::Deserialize;
use tracing_subscriber::{
    EnvFilter, Registry, fmt as tracing_fmt, layer::SubscriberExt, reload, util::SubscriberInitExt,
};

/// Content of `logging.conf`. Every setting has a default, unknown keys are rejected.
#[derive(Debug, Default, Clone, PartialEq, Eq, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct LogConfig {
    pub general: LoggerConfig,
}

impl FromStr for LogConfig {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        Ok(toml::from_str(s)?)
    }
}

impl LogConfig {
    /// Reads `logging.conf` in the configuration directory
    pub fn load(dir: impl AsRef<Path>) -> Result<Self> {
        let path = dir.as_ref().join("logging.conf");
        std::fs::read_to_string(&path)
            .with_context(|| format!("could not read configuration file {}", path.display()))?
            .parse()
            .with_context(|| format!("invalid configuration file {}", path.display()))
    }
}

/// As a tracing `EnvFilter` directive
impl fmt::Display for LogConfig {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(f, "{}", self.general)
    }
}

#[derive(Debug, Default, Clone, PartialEq, Eq, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct LoggerConfig {
    /// Global log level
    pub level: LogLevel,
    /// Additional filters, e.g. `rmcp=debug` or `[database{node=root}]=trace`
    pub filter: LogFilter,
}

impl fmt::Display for LoggerConfig {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        if self.filter.0.is_empty() {
            write!(f, "{}", self.level)
        } else {
            write!(f, "{},{}", self.level, self.filter.0)
        }
    }
}

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum LogLevel {
    Off,
    Error,
    Warn,
    #[default]
    Info,
    Debug,
    Trace,
}

impl fmt::Display for LogLevel {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        f.write_str(match self {
            Self::Off => "off",
            Self::Error => "error",
            Self::Warn => "warn",
            Self::Info => "info",
            Self::Debug => "debug",
            Self::Trace => "trace",
        })
    }
}

/// Comma-separated tracing filter directives
#[derive(Debug, Default, Clone, PartialEq, Eq, Deserialize)]
#[serde(try_from = "String")]
pub struct LogFilter(String);

impl TryFrom<String> for LogFilter {
    type Error = String;

    fn try_from(filter: String) -> Result<Self, Self::Error> {
        EnvFilter::builder()
            .parse(&filter)
            .map_err(|e| format!("invalid log filter '{filter}': {e}"))?;
        Ok(Self(filter))
    }
}

/// Changes the log filter of the running daemon
#[derive(Debug, Clone)]
pub struct LogHandle(reload::Handle<EnvFilter, Registry>);

impl LogHandle {
    pub fn reload(&self, config: &LogConfig) -> Result<()> {
        // Validated when parsing the configuration
        let filter = EnvFilter::try_new(config.to_string())?;
        Ok(self.0.reload(filter)?)
    }
}

/// Sets the global logger: only errors until the configuration is loaded and given to
/// `LogHandle::reload`
pub fn init() -> Result<LogHandle> {
    let (filter, handle) = reload::Layer::new(EnvFilter::new("error"));
    tracing_subscriber::registry()
        .with(filter)
        .with(tracing_fmt::layer().without_time().with_ansi(false))
        .try_init()?;
    Ok(LogHandle(handle))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn parse(content: &str) -> Result<LogConfig, String> {
        content.parse().map_err(|e: Error| format!("{e:#}"))
    }

    #[test]
    fn empty_file_gives_defaults() {
        assert_eq!(parse("").unwrap(), LogConfig::default());
        assert_eq!(parse("[general]\n").unwrap(), LogConfig::default());
        assert_eq!(LogConfig::default().to_string(), "info");
    }

    #[test]
    fn full_file() {
        let config = parse(
            r#"
            [general]
            level = "warn"
            filter = "[database{node=root}]=trace,rmcp=debug"
            "#,
        )
        .unwrap();
        assert_eq!(
            config.to_string(),
            "warn,[database{node=root}]=trace,rmcp=debug"
        );
    }

    #[test]
    fn rejects_invalid_values() {
        let error = parse("[general]\nlevel = \"verbose\"\n").unwrap_err();
        assert!(error.contains("unknown variant `verbose`"), "{error}");
        let error = parse("[general]\nfilter = \"rmcp=loud\"\n").unwrap_err();
        assert!(error.contains("invalid log filter 'rmcp=loud'"), "{error}");
        let error = parse("[general]\nlevle = \"debug\"\n").unwrap_err();
        assert!(error.contains("unknown field `levle`"), "{error}");
    }

    #[test]
    fn load_reports_missing_file() {
        let error = LogConfig::load("/nonexistent").unwrap_err();
        assert!(
            error
                .to_string()
                .contains("could not read configuration file /nonexistent/logging.conf"),
            "{error}"
        );
    }
}
