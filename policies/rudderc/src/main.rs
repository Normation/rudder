// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

use std::{env, process::exit};

use clap::Parser;
use rudderc::{cli::MainArgs, logs};
use tracing::{debug, error, trace};

fn main() {
    // https://www.reddit.com/r/rust/comments/bnqina/why_does_not_rust_give_a_backtrace_by_default/
    // https://internals.rust-lang.org/t/rust-backtrace-in-production-use/5609/2
    // May be expensive only when backtraces are actually produced
    // and can be helpful to troubleshoot production crashes
    if env::var_os("RUST_BACKTRACE").is_none() {
        // Set default value, others are "0" and "full"
        env::set_var("RUST_BACKTRACE", "1");
    }

    let args = MainArgs::parse();
    logs::init(args.verbose, args.quiet, args.message_format);
    debug!(
        "Running {} v{}",
        env!("CARGO_PKG_NAME"),
        env!("CARGO_PKG_VERSION")
    );
    trace!("Arguments:\n{:#?}", args);

    match rudderc::run(args) {
        Ok(_) => (),
        Err(e) => {
            // Display errors with logger
            // Use `Debug` to display anyhow error chain
            error!("{:?}", e);
            exit(1);
        }
    }
}
