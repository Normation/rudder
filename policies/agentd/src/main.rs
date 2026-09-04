// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

/*! We have single main here and not one main per binary.
 *  This is because it is not possible to build 2 different architectures at the same time
 *  So there will always be a single main target.
 */

#[cfg(target_os = "windows")]
mod windows;

#[cfg(target_os = "linux")]
mod linux;

use clap::Parser;
use log::error;

#[derive(Parser, Debug)]
#[command(name = "agentd")]
#[command(version, about = "Rudder agentd cli", long_about = None)]
struct Args {
    /// command to get next run time of a job
    #[arg(short, long)]
    get_next_run: Option<String>,
}

impl Args {
    fn cli_arg(&self) -> bool {
        self.get_next_run.is_none()
    }

    fn handle_cli(&self) {
        if let Some(job) = &self.get_next_run {
            #[cfg(target_os = "windows")]
            let scheduler = windows::init_scheduler();
            #[cfg(target_os = "linux")]
            let scheduler = todo!();
            match scheduler.get_next_run(&job) {
                Ok(next_run) => {
                    println!("{}", next_run.to_rfc3339());
                }
                Err(e) => {
                    error!("Failed to get next run for {}: {}", job, e);
                }
            }
        }
    }
}

// Do no use tokio::main since the async part might be behind non-async function
// This also simplifies common code by putting everything inside the main loop
fn main() {
    // detect cli calls
    let args = Args::parse();
    if args.cli_arg() {
        return args.handle_cli();
    }

    // regular daemon
    #[cfg(target_os = "windows")]
    return windows::main();
    #[cfg(target_os = "linux")]
    return linux::main();
}
