// SPDX-License-Identifier: GPL-3.0-or-later WITH GPL-3.0-linking-source-exception
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use std::{env, process::exit};

use gumdrop::Options;
use rudder_relayd::{
    configuration::{check_configuration, cli::CliConfiguration},
    init_logger, start, ExitStatus, CRATE_NAME, CRATE_VERSION,
};
use tracing::error;

/// Everything in a lib to allow extensive testing
fn main() {
    // https://www.reddit.com/r/rust/comments/bnqina/why_does_not_rust_give_a_backtrace_by_default/
    // https://internals.rust-lang.org/t/rust-backtrace-in-production-use/5609/2
    // May be expensive only when backtraces are actually produced
    // and can be helpful to troubleshoot production crashes
    if env::var_os("RUST_BACKTRACE").is_none() {
        // Set default value, others are "0" and "full"
        env::set_var("RUST_BACKTRACE", "1");
    }

    let cli_cfg = CliConfiguration::parse_args_default_or_exit();

    if cli_cfg.version {
        println!("{} {}", CRATE_NAME, CRATE_VERSION);
    } else if cli_cfg.test {
        match check_configuration(&cli_cfg.config) {
            Err(e) => {
                println!("{}", e);
                exit(ExitStatus::StartError(e).code());
            }
            Ok(warns) => {
                for w in warns {
                    println!("warning: {}", w);
                }
                println!("Syntax: OK");
            }
        }
    } else {
        let reload_handle = match init_logger() {
            Ok(handle) => handle,
            Err(e) => {
                println!("{}", e);
                exit(ExitStatus::StartError(e).code());
            }
        };

        if let Err(e) = start(cli_cfg, reload_handle) {
            // Debug to get anyhow error stack
            error!("{:?}", e);
            exit(ExitStatus::StartError(e).code());
        }
    }
}
