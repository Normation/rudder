// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use crate::error::*;
use lazy_static::lazy_static;
use serde::{Deserialize, Serialize};
use std::sync::RwLock;
use std::{fmt, str::FromStr};

// to keep LOGS usage as safe as possible, make sure it is only called from this file
// any outside module call is just a push that happens through macros
// and a clone to get a safe clone of the global to work on from outside the module

lazy_static! {
    pub static ref LOGS: RwLock<Logs> = RwLock::new(Logs {
        logs: Vec::new(),
        log_level: LogLevel::Warn,
    });
}

pub fn clone_logs() -> Logs {
    LOGS.read().expect("bug: failed to read logs").clone()
}

// pub static mut LOGS.log_level: LogLevel = LogLevel::Warn;
pub fn set_global_log_level(log_level: LogLevel) {
    LOGS.write()
        .expect("bug: failed to update logs")
        .update_log_level(log_level)
}

macro_rules! error {
    ($ ( $ arg : tt ) *) => ({
        use crate::logger::LOGS;
        if LOGS.read().expect("bug: failed to read logs").is_level_included(LogLevel::Error) {
            LOGS.write().expect("bug: failed to update logs").push(LogLevel::Error, format!( $ ( $ arg ) * ));
        }
    });
}

macro_rules! warn {
    ($ ( $ arg : tt ) *) => ({
        use crate::logger::LOGS;
        if LOGS.read().expect("bug: failed to read logs").is_level_included(LogLevel::Warn) {
            LOGS.write().expect("bug: failed to update logs").push(LogLevel::Warn, format!( $ ( $ arg ) * ));
        }
    });
}

macro_rules! info {
    ($ ( $ arg : tt ) *) => ({
        use crate::logger::LOGS;
        if LOGS.read().expect("bug: failed to read logs").is_level_included(LogLevel::Info) {
            LOGS.write().expect("bug: failed to update logs").push(LogLevel::Info, format!( $ ( $ arg ) * ));
        }
    });
}

macro_rules! debug {
    ($ ( $ arg : tt ) *) => ({
        use crate::logger::LOGS;
        if LOGS.read().expect("bug: failed to read logs").is_level_included(LogLevel::Debug) {
            LOGS.write().expect("bug: failed to update logs").push(LogLevel::Debug, format!( $ ( $ arg ) * ));
        }
    });
}

macro_rules! trace {
    ($ ( $ arg : tt ) *) => ({
        use crate::logger::LOGS;
        if LOGS.read().expect("bug: failed to read logs").is_level_included(LogLevel::Trace) {
            LOGS.write().expect("bug: failed to update logs").push(LogLevel::Trace, format!( $ ( $ arg ) * ));
        }
    });
}

#[derive(Serialize, Clone)]
struct Log {
    level: LogLevel,
    message: String,
}
impl Log {
    fn new(level: LogLevel, message: String) -> Self {
        Self { level, message }
    }
}
impl fmt::Display for Log {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        // update final log output from here
        write!(f, "{}: {}", self.level, self.message)
    }
}

/// Logs is used through our static, global variable LOGS throughought the program
/// all logs are pushed into it, be it errors, informations, debug
/// they are effectively printed at program exit using the output format, even in case of panic
#[derive(Serialize, Clone)]
pub struct Logs {
    logs: Vec<Log>,
    #[serde(skip)]
    log_level: LogLevel,
}
impl Logs {
    pub fn push(&mut self, level: LogLevel, msg: String) {
        self.logs.push(Log::new(level, msg))
    }

    pub fn is_level_included(&self, other: LogLevel) -> bool {
        self.log_level.includes(other)
    }

    fn update_log_level(&mut self, log_level: LogLevel) {
        self.log_level = log_level;
    }
}
impl fmt::Display for Logs {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        let logs_as_string = self
            .logs
            .iter()
            .map(|log| format!("{}", log))
            .collect::<Vec<String>>()
            .join("\n");
        write!(f, "{}", logs_as_string)
    }
}

#[derive(Serialize, Deserialize, Copy, Clone, Debug, Eq, PartialEq, Ord, PartialOrd)]
pub enum LogLevel {
    Error,
    Warn,
    Info,
    Debug,
    Trace,
}
impl LogLevel {
    pub fn includes(&self, other: LogLevel) -> bool {
        self >= &other
    }
}
impl Default for LogLevel {
    fn default() -> Self {
        Self::Warn
    }
}
impl fmt::Display for LogLevel {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "{}",
            match self {
                Self::Error => "ERROR",
                Self::Warn => "WARN",
                Self::Info => "INFO",
                Self::Debug => "DEBUG",
                Self::Trace => "TRACE",
            }
        )
    }
}
impl FromStr for LogLevel {
    type Err = Error;

    fn from_str(input: &str) -> Result<Self> {
        match input.to_lowercase().as_ref() {
            "error" => Ok(Self::Error),
            "warn" => Ok(Self::Warn),
            "info" => Ok(Self::Info),
            "debug" => Ok(Self::Debug),
            "trace" => Ok(Self::Trace),
            _ => Err(Error::new(format!("Could not parse log level '{}'", input))),
        }
    }
}
