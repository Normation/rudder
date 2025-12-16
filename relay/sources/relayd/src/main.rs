// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use std::{path::Path, process::exit};

use clap::Parser;
use rudder_relayd::{
    configuration::{check_configuration, cli::CliConfiguration},
    init_logger, start, ExitStatus,
};
use tracing::error;

/// Everything in a lib to allow extensive testing
fn main() {
    let cli_cfg = CliConfiguration::parse();
    if cli_cfg.test {
        match check_configuration(Path::new(&cli_cfg.config)) {
            Err(e) => {
                println!("{e}");
                exit(ExitStatus::StartError(e).code());
            }
            Ok(warns) => {
                for w in warns {
                    println!("warning: {w}");
                }
                println!("Syntax: OK");
            }
        }
    } else {
        let reload_handle = match init_logger() {
            Ok(handle) => handle,
            Err(e) => {
                println!("{e}");
                exit(ExitStatus::StartError(e).code());
            }
        };

        if let Err(e) = start(cli_cfg, reload_handle, None) {
            // Debug to get anyhow error stack
            error!("{:?}", e);
            exit(ExitStatus::StartError(e).code());
        }
    }
}
