// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use std::path::{Path, PathBuf};

#[derive(StructOpt, Debug)]
#[structopt(name = "rudder-relayd")]
#[structopt(author, about)]
// version and description are taken from Cargo.toml
// struct fields comments are used as option description in help
pub struct CliConfiguration {
    /// Sets a custom config directory
    #[structopt(
        short = "c",
        long = "config",
        default_value = "/opt/rudder/etc/relayd/",
        parse(from_os_str)
    )]
    pub configuration_dir: PathBuf,

    /// Checks the syntax of the configuration file and exit
    #[structopt(short = "t", long = "test")]
    pub check_configuration: bool,
}

impl CliConfiguration {
    /// Used to generate configurations in tests
    pub fn new<P: AsRef<Path>>(path: P, check_configuration: bool) -> Self {
        Self {
            configuration_dir: path.as_ref().to_path_buf(),
            check_configuration,
        }
    }
}
