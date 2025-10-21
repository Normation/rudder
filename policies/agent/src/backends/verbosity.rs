// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS
use std::fmt::Display;

#[derive(Debug, Clone, Copy)]
pub enum Verbosity {
    Verbose,
    Info,
}
impl Verbosity {
    pub fn to_flag(&self) -> String {
        match self {
            Verbosity::Verbose => "--verbose".to_string(),
            Verbosity::Info => "--info".to_string(),
        }
    }
}

impl Display for Verbosity {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Verbosity::Verbose => write!(f, "verbose"),
            Verbosity::Info => write!(f, "info"),
        }
    }
}
