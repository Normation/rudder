// SPDX-License-Identifier: GPL-3.0-or-later WITH GPL-3.0-linking-source-exception
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use std::path::{Path, PathBuf};

use gumdrop::Options;

#[derive(Debug, Options)]
// version and description are taken from Cargo.toml
// struct fields comments are used as option description in help
pub struct CliConfiguration {
    #[options(
        help = "set a custom config directory",
        default = "/opt/rudder/etc/relayd/"
    )]
    pub config: PathBuf,
    #[options(help = "check the syntax of the configuration file and exit")]
    pub test: bool,
    /// Automatically used for help flag
    #[options(help = "print help message")]
    help: bool,
    #[options(help = "print version", short = "V")]
    pub version: bool,
}

impl CliConfiguration {
    /// Used to generate configurations in tests
    pub fn new<P: AsRef<Path>>(path: P, test: bool) -> Self {
        Self {
            config: path.as_ref().to_path_buf(),
            test,
            help: false,
            version: false,
        }
    }
}
