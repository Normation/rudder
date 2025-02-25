// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Normation SAS

//! Maps CFEngine's log levels

use std::{
    cmp, fmt, mem,
    str::FromStr,
    sync::atomic::{AtomicUsize, Ordering},
};

use anyhow::{Error, bail};
use serde::{Deserialize, Deserializer, Serialize};

#[derive(Debug, Serialize, Clone, Copy, Eq)]
#[serde(rename_all = "lowercase")]
/// Here we don't use the basic levels of the `log` crate as we want to
/// match CFEngine's levels as well as converge with our Windows implementation.
pub enum Level {
    Critical, // alias Fatal
    Error,
    Warning,
    // We don't expose "notice" level logs
    Info,  // info = notice
    Debug, // -> Verbose
    Trace, // -> Debug
}

/// Matching CFEngine level
impl fmt::Display for Level {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "{}",
            match self {
                Level::Critical => "critical",
                Level::Error => "error",
                Level::Warning => "warning",
                Level::Info => "info",
                Level::Debug => "verbose",
                Level::Trace => "debug",
            }
        )
    }
}

#[derive(Debug, PartialEq, Serialize, Clone, Copy, Eq)]
#[repr(usize)]
#[serde(rename_all = "lowercase")]
pub enum LevelFilter {
    Critical,
    Error,
    Warning,
    Info,
    Debug,
    Trace,
}

impl FromStr for LevelFilter {
    type Err = Error;

    fn from_str(level: &str) -> anyhow::Result<Self> {
        Ok(match level {
            "critical" => LevelFilter::Critical,
            "error" => LevelFilter::Error,
            "warning" => LevelFilter::Warning,
            "notice" => LevelFilter::Info,
            "info" => LevelFilter::Info,
            "verbose" => LevelFilter::Debug,
            "debug" => LevelFilter::Trace,
            _ => bail!("Could not recognize log level {:?}", level),
        })
    }
}

impl<'de> Deserialize<'de> for LevelFilter {
    fn deserialize<D>(deserializer: D) -> core::result::Result<Self, D::Error>
    where
        D: Deserializer<'de>,
    {
        let fmt = String::deserialize(deserializer)?;
        LevelFilter::from_str(&fmt).map_err(serde::de::Error::custom)
    }
}

impl Ord for Level {
    #[inline]
    fn cmp(&self, other: &Level) -> cmp::Ordering {
        (*self as usize).cmp(&(*other as usize))
    }
}

impl PartialEq for Level {
    #[inline]
    fn eq(&self, other: &Level) -> bool {
        *self as usize == *other as usize
    }
}

impl PartialEq<LevelFilter> for Level {
    #[inline]
    fn eq(&self, other: &LevelFilter) -> bool {
        *self as usize == *other as usize
    }
}

impl PartialOrd for Level {
    #[inline]
    fn partial_cmp(&self, other: &Level) -> Option<cmp::Ordering> {
        Some(self.cmp(other))
    }

    #[inline]
    fn lt(&self, other: &Level) -> bool {
        (*self as usize) < *other as usize
    }

    #[inline]
    fn le(&self, other: &Level) -> bool {
        *self as usize <= *other as usize
    }

    #[inline]
    fn gt(&self, other: &Level) -> bool {
        *self as usize > *other as usize
    }

    #[inline]
    fn ge(&self, other: &Level) -> bool {
        *self as usize >= *other as usize
    }
}

impl PartialOrd<LevelFilter> for Level {
    #[inline]
    fn partial_cmp(&self, other: &LevelFilter) -> Option<cmp::Ordering> {
        Some((*self as usize).cmp(&(*other as usize)))
    }

    #[inline]
    fn lt(&self, other: &LevelFilter) -> bool {
        (*self as usize) < *other as usize
    }

    #[inline]
    fn le(&self, other: &LevelFilter) -> bool {
        *self as usize <= *other as usize
    }

    #[inline]
    fn gt(&self, other: &LevelFilter) -> bool {
        *self as usize > *other as usize
    }

    #[inline]
    fn ge(&self, other: &LevelFilter) -> bool {
        *self as usize >= *other as usize
    }
}

// Below is a slightly modified version of the macros from the `log` crate.

// Copyright 2014-2015 The Rust Project Developers. See the COPYRIGHT
// file at the top-level directory of this distribution and at
// http://rust-lang.org/COPYRIGHT.
//
// Licensed under the Apache License, Version 2.0 <LICENSE-APACHE or
// http://www.apache.org/licenses/LICENSE-2.0> or the MIT license
// <LICENSE-MIT or http://opensource.org/licenses/MIT>, at your
// option. This file may not be copied, modified, or distributed
// except according to those terms.

static MAX_LOG_LEVEL_FILTER: AtomicUsize = AtomicUsize::new(0);

#[inline]
pub fn set_max_level(level: LevelFilter) {
    MAX_LOG_LEVEL_FILTER.store(level as usize, Ordering::SeqCst)
}

#[inline(always)]
pub fn max_level() -> LevelFilter {
    // Since `LevelFilter` is `repr(usize)`,
    // this transmute is sound if and only if `MAX_LOG_LEVEL_FILTER`
    // is set to a usize that is a valid discriminant for `LevelFilter`.
    // Since `MAX_LOG_LEVEL_FILTER` is private, the only time it's set
    // is by `set_max_level` above, i.e. by casting a `LevelFilter` to `usize`.
    // So any usize stored in `MAX_LOG_LEVEL_FILTER` is a valid discriminant.
    unsafe { mem::transmute(MAX_LOG_LEVEL_FILTER.load(Ordering::Relaxed)) }
}

// We only support single_line logs
// TODO: allow multi-line? We don't want to use JSON logs as we want to display them in live
pub fn escape_lines(s: &str) -> String {
    s.replace('\n', "\\n")
}

#[doc(hidden)]
#[macro_export(local_inner_macros)]
macro_rules! rudder_log {
    (target: $target:expr, $lvl:expr, $($arg:tt)+) => ({
        let lvl = $lvl;
        if lvl <= $crate::cfengine::log::max_level() {
            let f = std::format!("{}", __log_format_args!($($arg)+));

            std::println!(
                "log_{}={}",
                lvl, $crate::cfengine::log::escape_lines(&f)
            );
        }
    });
    ($lvl:expr, $($arg:tt)+) => (rudder_log!(target: __log_module_path!(), $lvl, $($arg)+))
}

/// Serious errors in protocol or module itself (not in policy)
#[macro_export(local_inner_macros)]
macro_rules! rudder_critical {
    (target: $target:expr, $($arg:tt)+) => (
        rudder_log!(target: $target, $crate::cfengine::log::Level::Critical, $($arg)+)
    );
    ($($arg:tt)+) => (
        rudder_log!($crate::cfengine::Level::Critical, $($arg)+)
    )
}

///  Errors when validating / evaluating a promise, including syntax errors and promise not kept
#[macro_export(local_inner_macros)]
macro_rules! rudder_error {
    (target: $target:expr, $($arg:tt)+) => (
        rudder_log!(target: $target, $crate::cfengine::log::Level::Error, $($arg)+)
    );
    ($($arg:tt)+) => (
        rudder_log!($crate::cfengine::log::Level::Error, $($arg)+)
    )
}

/// The promise did not fail, but there is something the user (policy writer) should probably fix. Some examples:
///
/// * Policy relies on deprecated behavior/syntax which will change
/// * Policy uses demo / unsafe options which should be avoided in a production environment
#[macro_export(local_inner_macros)]
macro_rules! rudder_warning {
    (target: $target:expr, $($arg:tt)+) => (
        rudder_log!(target: $target, $crate::cfengine::log::Level::Warning, $($arg)+)
    );
    ($($arg:tt)+) => (
        rudder_log!($crate::cfengine::log::Level::Warning, $($arg)+)
    )
}

/// Changes made to the system (usually 1 per repaired promise, more if the promise made multiple different changes to the system)
#[macro_export(local_inner_macros)]
macro_rules! rudder_info {
    (target: $target:expr, $($arg:tt)+) => (
        rudder_log!(target: $target, $crate::cfengine::log::Level::Info, $($arg)+)
    );
    ($($arg:tt)+) => (
        rudder_log!($crate::cfengine::log::Level::Info, $($arg)+)
    )
}

/// Human understandable detailed information about promise evaluation
#[macro_export(local_inner_macros)]
macro_rules! rudder_debug {
    (target: $target:expr, $($arg:tt)+) => (
        rudder_log!(target: $target, $crate::cfengine::log::Level::Debug, $($arg)+)
    );
    ($($arg:tt)+) => (
        rudder_log!($crate::cfengine::log::Level::Debug, $($arg)+)
    )
}

/// Programmer-level information that is only useful for agent or module library developers
#[macro_export(local_inner_macros)]
macro_rules! rudder_trace {
    (target: $target:expr, $($arg:tt)+) => (
        rudder_log!(target: $target, $crate::cfengine::log::Level::Trace, $($arg)+)
    );
    ($($arg:tt)+) => (
        rudder_log!($crate::cfengine::log::Level::Trace, $($arg)+)
    )
}

#[doc(hidden)]
#[macro_export]
macro_rules! __log_format_args {
    ($($args:tt)*) => {
        format_args!($($args)*)
    };
}
