// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use std::ffi::OsStr;
use std::ops::Deref;

pub mod comparator;
mod error;
mod interpreter;
mod ip;
mod parser;
mod parser_chumsky;
mod password;
pub mod repl;
pub mod script;
mod value_type;

pub type Value<'a> = &'a str;
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

impl AugPath<'_> {
    pub fn is_absolute(&self) -> bool {
        self.inner.starts_with('/')
    }

    pub fn is_relative(&self) -> bool {
        !self.is_absolute()
    }
}

#[cfg(test)]
mod tests {
    use crate::dsl::parser::{arg, comment};
    #[test]
    fn test_comment() {
        let input = "# This is a comment";
        let expected = " This is a comment";
        let result = comment(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_arg_with_quoted_value() {
        let input = r#""quoted value""#;
        let expected = "quoted value";
        let result = arg(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_arg_with_single_quoted_value() {
        let input = r#"'quoted value'"#;
        let expected = "quoted value";
        let result = arg(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_arg_with_unquoted_value() {
        let input = "unquoted value";
        let expected = "unquoted";
        let result = arg(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_arg_with_empty_input() {
        let input = "";
        let result = arg(input);
        assert!(result.is_err());
    }
}
