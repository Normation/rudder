// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

mod levels;
pub use levels::LogLevel;
#[macro_use]
mod macros;

use lazy_static::lazy_static;
use serde::Serialize;
use std::fmt;
use std::sync::RwLock;

// to keep LOGS usage as safe as possible, make sure it is only called from this file
// any outside module call is just a push that happens through macros
// and a clone to get a safe clone of the global to work on from outside the module
// should never be used outside of the crate
lazy_static! {
    pub static ref LOGS: RwLock<Logs> = RwLock::new(Logs {
        logs: Vec::new(),
        log_level: LogLevel::Warn,
    });
}

pub(super) fn clone_logs() -> Logs {
    LOGS.read().expect("bug: failed to read logs").clone()
}

// pub static mut LOGS.log_level: LogLevel = LogLevel::Warn;
pub(super) fn set_global_log_level(log_level: LogLevel) {
    LOGS.write()
        .expect("bug: failed to update logs")
        .update_log_level(log_level)
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
// Logs content should stay private since we do not want unsafe code elsewhere in the program
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
