// Copyright 2019 Normation SAS
//
// This file is part of Rudder.
//
// Rudder is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// In accordance with the terms of section 7 (7. Additional Terms.) of
// the GNU General Public License version 3, the copyright holders add
// the following Additional permissions:
// Notwithstanding to the terms of section 5 (5. Conveying Modified Source
// Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
// Public License version 3, when you create a Related Module, this
// Related Module is not considered as a part of the work and may be
// distributed under the license agreement of your choice.
// A "Related Module" means a set of sources files including their
// documentation that, without modification of the Source Code, enables
// supplementary functions or services in addition to those offered by
// the Software.
//
// Rudder is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

use crate::error::Error;
use serde::Deserialize;
use std::{fmt, fs::read_to_string, path::Path, str::FromStr};
use toml;
use tracing::debug;

#[derive(Deserialize, Debug, PartialEq, Eq, Clone)]
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
    pub fn new<P: AsRef<Path>>(path: P) -> Result<Self, Error> {
        let res = read_to_string(path.as_ref().join("logging.conf"))?.parse::<Self>();
        if let Ok(ref cfg) = res {
            debug!("Parsed logging configuration:\n{:#?}", &cfg);
        }
        res
    }
}

impl fmt::Display for LogConfig {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(f, "{}", self.general)
    }
}

#[derive(Copy, Debug, Eq, PartialEq, Deserialize, Clone)]
#[serde(rename_all = "lowercase")]
pub enum LogLevel {
    Error,
    Warn,
    Info,
    Debug,
    Trace,
}

impl fmt::Display for LogLevel {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(
            f,
            "{}",
            match self {
                LogLevel::Error => "error",
                LogLevel::Warn => "warn",
                LogLevel::Info => "info",
                LogLevel::Debug => "debug",
                LogLevel::Trace => "trace",
            }
        )
    }
}

#[derive(Deserialize, Debug, PartialEq, Eq, Clone)]
pub struct LoggerConfig {
    #[serde(with = "LogLevel")]
    pub level: LogLevel,
    pub filter: String,
}

impl fmt::Display for LoggerConfig {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        if self.filter.is_empty() {
            write!(f, "{}", self.level)
        } else {
            write!(f, "{},{}", self.level, self.filter)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn it_generates_log_configuration() {
        let log_reference = LogConfig {
            general: LoggerConfig {
                level: LogLevel::Info,
                filter: "".to_string(),
            },
        };
        assert_eq!(&log_reference.to_string(), "info");

        let log_reference = LogConfig {
            general: LoggerConfig {
                level: LogLevel::Info,
                filter: "[database{node=root}]=trace".to_string(),
            },
        };
        assert_eq!(
            &log_reference.to_string(),
            "info,[database{node=root}]=trace"
        );
    }

    #[test]
    fn it_parses_logging_configuration() {
        let log_config = LogConfig::new("tests/files/config/");
        let log_reference = LogConfig {
            general: LoggerConfig {
                level: LogLevel::Info,
                filter: "".to_string(),
            },
        };
        assert_eq!(log_config.unwrap(), log_reference);
    }
}
