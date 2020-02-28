// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

pub mod cli;
pub mod logging;
pub mod main;

use serde::Deserialize;
use std::fmt;

/// Allows hiding a value in logs
#[derive(Deserialize, PartialEq, Eq, Clone, Default)]
#[serde(transparent)]
pub struct Secret {
    value: String,
}

impl<'a> Secret {
    pub fn new(value: String) -> Self {
        Self { value }
    }

    pub fn value(&'a self) -> &'a str {
        &self.value
    }
}

impl fmt::Display for Secret {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "******")
    }
}

impl fmt::Debug for Secret {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "\"******\"")
    }
}
