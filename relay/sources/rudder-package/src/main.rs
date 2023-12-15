// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

use std::process;

fn main() {
    if rudder_package::run().is_err() {
        process::exit(1);
    }
}
