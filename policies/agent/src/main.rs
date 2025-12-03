// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS
use std::process::ExitCode;

fn main() -> ExitCode {
    match agent::run() {
        Err(e) => {
            println!("{:?}", e);
            ExitCode::FAILURE
        }
        Ok(_) => ExitCode::SUCCESS,
    }
}
