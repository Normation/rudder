// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

//! Output of the compiler
//!
//! The style is heavily inspired from cargo/rustc.

use std::io;

use env_logger::fmt::{Color, Formatter, Style, StyledValue};
use log::{Level, LevelFilter, Record};

fn colored_level(style: &mut Style, level: Level) -> StyledValue<&'static str> {
    match level {
        Level::Warn => style
            .set_color(Color::Yellow)
            .set_bold(true)
            .value("warning"),
        Level::Error => style.set_color(Color::Red).set_bold(true).value("error"),
        _ => unreachable!(),
    }
}

fn format(f: &mut Formatter, record: &Record) -> io::Result<()> {
    use std::io::Write;

    match record.level() {
        // Simply write non-error messages
        Level::Info | Level::Debug | Level::Trace => writeln!(f, "{}", record.args()),
        // Prefix warn/error by severity to make them stand out
        level => {
            let mut style = f.style();
            writeln!(f, "{}: {}", colored_level(&mut style, level), record.args(),)
        }
    }
}

pub fn init(verbose: u8, quiet: bool) {
    let filter = match (verbose, quiet) {
        (0, true) => LevelFilter::Warn,
        (0, false) => LevelFilter::Info,
        (1, _) => LevelFilter::Debug,
        (_, _) => LevelFilter::Trace,
    };
    env_logger::builder()
        .filter_level(filter)
        .format(format)
        .init();
}
