// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

use std::process::exit;

use clap::Parser;
use log::{debug, error, trace};
use rudderc::{cli::MainArgs, logs};

fn main() {
    let args = MainArgs::parse();
    logs::init(args.verbose, args.quiet);
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
