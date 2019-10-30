// Copyright 2019 Normation SAS
//
// This file is part of Rudder.
//
// Rudder is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// In accordance with the terms of section 7 (7. Additional Terms.) of
// the GNU General Public License version 3, the copyright holders add
// the following Additional permissions:
// Notwithstanding to the terms of section 5 (5. Conveying Modified Source
// Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
// Public License version 3, when you create a Related Module, this
// Related Module is not considered as a part of the work and may be
// distributed under the license agreement of your choice.
// A "Related Module" means a set of sources files including their
// documentation that, without modification of the Source Code, enables
// supplementary functions or services in addition to those offered by
// the Software.
//
// Rudder is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

use relayd::{
    check_configuration, configuration::cli::CliConfiguration, init_logger, start, ExitStatus,
};
use std::{env, process::exit};
use structopt::StructOpt;
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

    let cli_cfg = CliConfiguration::from_args();
    if cli_cfg.check_configuration {
        if let Err(e) = check_configuration(&cli_cfg.configuration_dir) {
            println!("{}", e);
            exit(ExitStatus::StartError(e).code());
        }
        println!("Syntax: OK");
    } else {
        let reload_handle = match init_logger() {
            Ok(handle) => handle,
            Err(e) => {
                println!("{}", e);
                exit(ExitStatus::StartError(e).code());
            }
        };

        if let Err(e) = start(cli_cfg, reload_handle) {
            error!("{}", e);
            exit(ExitStatus::StartError(e).code());
        }
    }
}
