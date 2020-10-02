// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

macro_rules! error {
    ($ ( $ arg : tt ) *) => ({
        use crate::logs::{LOGS, LogLevel};
        if LOGS.read().expect("bug: failed to read logs").is_level_included(LogLevel::Error) {
            LOGS.write().expect("bug: failed to update logs").push(LogLevel::Error, format!( $ ( $ arg ) * ));
        }
    });
}

macro_rules! warn {
    ($ ( $ arg : tt ) *) => ({
        use crate::logs::{LOGS, LogLevel};
        if LOGS.read().expect("bug: failed to read logs").is_level_included(LogLevel::Warn) {
            LOGS.write().expect("bug: failed to update logs").push(LogLevel::Warn, format!( $ ( $ arg ) * ));
        }
    });
}

macro_rules! info {
    ($ ( $ arg : tt ) *) => ({
        use crate::logs::{LOGS, LogLevel};
        if LOGS.read().expect("bug: failed to read logs").is_level_included(LogLevel::Info) {
            LOGS.write().expect("bug: failed to update logs").push(LogLevel::Info, format!( $ ( $ arg ) * ));
        }
    });
}

macro_rules! debug {
    ($ ( $ arg : tt ) *) => ({
        use crate::logs::{LOGS, LogLevel};
        if LOGS.read().expect("bug: failed to read logs").is_level_included(LogLevel::Debug) {
            LOGS.write().expect("bug: failed to update logs").push(LogLevel::Debug, format!( $ ( $ arg ) * ));
        }
    });
}

macro_rules! trace {
    ($ ( $ arg : tt ) *) => ({
        use crate::logs::{LOGS, LogLevel};
        if LOGS.read().expect("bug: failed to read logs").is_level_included(LogLevel::Trace) {
            LOGS.write().expect("bug: failed to update logs").push(LogLevel::Trace, format!( $ ( $ arg ) * ));
        }
    });
}
