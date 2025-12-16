// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

use std::{env, process::exit};

use clap::Parser;
use rudder_cli::logs;
use rudderc::{cli::MainArgs, compiler::is_exit_on_user_error};
use tracing::{debug, error, trace};

fn main() {
    let args = MainArgs::parse();
    let _guard = logs::init(args.verbose, args.quiet, args.message_format, None);
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
            // USe `{:#}` formatter to get the error chain
            error!("{:#}", e);

            if is_exit_on_user_error() {
                exit(2);
            } else {
                exit(1);
            }
        }
    }
}
