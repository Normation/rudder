// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

#![allow(dead_code)]

fn main() -> anyhow::Result<()> {
    rudder_package::run()?;
    Ok(())
}
