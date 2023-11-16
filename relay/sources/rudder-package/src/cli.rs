// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

use clap::Parser;

use crate::CONFIG_PATH;

#[derive(Parser, Debug)]
#[command(author, version, about, long_about = None)]
pub struct Args {
    /// Configuration file path
    #[arg(short, long, default_value_t = CONFIG_PATH.into())]
    pub config: String,

    /// Enable verbose logs
    #[arg(short, long, default_value_t = false)]
    pub debug: bool,
}
