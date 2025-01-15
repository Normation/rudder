// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS ;

use anyhow::Result;

fn main() -> Result<(), anyhow::Error> {
    rudder_module_augeas::entry()
}
