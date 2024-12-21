// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use std::borrow::Cow;

mod changes;
mod checks;

/// A path in the Augeas tree.
#[derive(Debug, PartialEq)]
pub struct AugPath<'a> {
    inner: &'a str,
}

pub type Value<'a> = &'a str;
pub type Sub<'a> = &'a str;

impl<'a> From<&'a str> for AugPath<'a> {
    fn from(s: &'a str) -> Self {
        AugPath { inner: s }
    }
}

impl<'a> AugPath<'a> {
    pub fn new<T: AsRef<&'a str>>(path: T) -> AugPath<'a> {
        AugPath {
            inner: path.as_ref(),
        }
    }

    pub fn is_absolute(&self) -> bool {
        self.inner.starts_with('/')
    }

    pub fn is_relative(&self) -> bool {
        !self.is_absolute()
    }

    pub fn with_context(&self, context: Option<&str>) -> Cow<str> {
        match context {
            Some(c) if self.is_relative() => format!("{}/{}", c, self.inner).into(),
            _ => self.inner.into(),
        }
    }
}
