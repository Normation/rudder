// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use std::{ffi::OsStr, fmt::Display, ops::Deref};

pub mod comparator;
pub mod error;
pub mod interpreter;
mod ip;
mod parser;
mod password;
pub mod repl;
pub mod script;
mod value_type;

pub type Sub<'a> = &'a str;

/// A path in the Augeas tree.
#[derive(Debug, PartialEq, Clone)]
pub struct AugPath<'a> {
    inner: &'a str,
}

impl Deref for AugPath<'_> {
    type Target = str;

    fn deref(&self) -> &Self::Target {
        self.inner
    }
}

impl AsRef<OsStr> for AugPath<'_> {
    fn as_ref(&self) -> &OsStr {
        self.inner.as_ref()
    }
}

impl<'a> From<&'a str> for AugPath<'a> {
    fn from(s: &'a str) -> Self {
        AugPath { inner: s }
    }
}

impl Display for AugPath<'_> {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        self.inner.fmt(f)
    }
}

impl AugPath<'_> {
    pub fn is_absolute(&self) -> bool {
        self.inner.starts_with('/')
    }

    pub fn is_relative(&self) -> bool {
        !self.is_absolute()
    }
}
