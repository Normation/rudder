// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use clap::Parser;

#[derive(Parser, Debug)]
#[command(author, version, about, long_about = None)]
pub struct CliConfiguration {
    #[clap(long, short, help = "Set a custom configuration directory", default_value_t = String::from("/opt/rudder/etc/relayd"))]
    pub config: String,
    #[clap(
        long,
        short,
        help = "Check the syntax of the configuration file and exit"
    )]
    pub test: bool,
}

impl CliConfiguration {
    /// Used to generate configurations in tests
    pub fn new<P: AsRef<str>>(path: P, test: bool) -> Self {
        Self {
            config: path.as_ref().to_string(),
            test,
        }
    }
}
