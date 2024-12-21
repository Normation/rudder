// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use nom::branch::alt;
use nom::bytes::complete::{is_not, tag};
use nom::character::complete::{char, not_line_ending, space0};
use nom::error::VerboseError;
use nom::sequence::delimited;
use nom::IResult;
use std::borrow::Cow;
use std::ops::Deref;

pub mod changes;
pub mod checks;

pub type Value<'a> = &'a str;
pub type Sub<'a> = &'a str;

/// A path in the Augeas tree.
#[derive(Debug, PartialEq)]
pub struct AugPath<'a> {
    inner: &'a str,
}

impl Deref for AugPath<'_> {
    type Target = str;

    fn deref(&self) -> &Self::Target {
        self.inner
    }
}

impl<'a> From<&'a str> for AugPath<'a> {
    fn from(s: &'a str) -> Self {
        AugPath { inner: s }
    }
}

impl<'a> AugPath<'a> {
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

/// Read a comment.
///
/// A comment starts with a `#` and ends with a newline.
fn comment(input: &str) -> IResult<&str, &str, VerboseError<&str>> {
    let (input, _) = tag("#")(input)?;
    let (input, comment) = not_line_ending(input)?;
    Ok((input, comment))
}

/// Read a path or a value.
///
/// It can contain spaces, in which case it must be quoted.
fn arg(input: &str) -> IResult<&str, &str, VerboseError<&str>> {
    let (input, _) = space0(input)?;
    // either a quoted string or an unquoted string
    let (input, arg) = alt((
        // FIXME cleanup eol & delimiters
        delimited(char('"'), is_not("\"\r\n"), char('"')),
        delimited(char('\''), is_not("'\r\n"), char('\'')),
        is_not("\"' \t\r\n"),
    ))(input)?;
    Ok((input, arg))
}

#[cfg(test)]
mod tests {
    use super::*;

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
