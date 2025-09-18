// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

use std::process::ExitCode;

fn main() -> ExitCode {
    match rudder_package::run() {
        Err(_) => ExitCode::FAILURE,
        Ok(code) => code,
    }
}
