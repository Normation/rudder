// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

//! Output of the compiler
//!
//! The style is heavily inspired from cargo/rustc.

use tracing_subscriber::filter::LevelFilter;
use tracing_subscriber::fmt::Subscriber;
use tracing_subscriber::{fmt, EnvFilter};

pub fn init(verbose: u8, quiet: bool) {
    let level = match (verbose, quiet) {
        (0, true) => LevelFilter::WARN,
        (0, false) => LevelFilter::INFO,
        (1, _) => LevelFilter::DEBUG,
        (_, _) => LevelFilter::TRACE,
    };
    let filter = EnvFilter::builder()
        .from_env_lossy()
        .add_directive(level.into());

    let format = fmt::format().compact();

    let builder = Subscriber::builder()
        .event_format(format)
        .without_time()
        .with_env_filter(filter);
    builder.init();
}
