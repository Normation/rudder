// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

//#![deny(elided_lifetimes_in_paths)]

//! Implements two DSLs to define a safe subset of augeas commands.

use nom::branch::alt;
use nom::bytes::complete::{is_not, tag};
use nom::character::complete::{
    alpha1, char, line_ending, multispace0, not_line_ending, space0, space1,
};
use nom::combinator::{eof, not, opt};
use nom::multi::many0;
use nom::sequence::delimited;
use nom::{Finish, IResult};
use std::borrow::Cow;
use std::str::FromStr;

use anyhow::{anyhow, Result};
use nom::error::VerboseError;
use raugeas::Augeas;

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

    pub fn with_context(&self, context: Option<&str>) -> Cow<str> {
        match context {
            Some(c) => format!("{}/{}", c, self.inner).into(),
            None => self.inner.into(),
        }
    }
}

/// The ordered list of changes to apply.
///
/// FIXME: *excellent* reporting for each change.
/// FIXME: make it as close as possible to the augeas command line & srun syntax.
#[derive(Debug, PartialEq)]
pub struct Changes<'a> {
    changes: Vec<Change<'a>>,
}

impl<'a> Changes<'a> {
    pub fn from(input: &'a str) -> Result<Changes<'a>> {
        let (_, changes) = changes(&input)
            .finish()
            .map_err(|e| anyhow!(format!("{:?}", e)))?;
        Ok(Changes { changes })
    }

    pub fn evaluate(&self, context: Option<&str>, augeas: &mut Augeas) -> Result<()> {
        for change in &self.changes {
            change.evaluate(context, augeas)?;
        }
        Ok(())
    }
}

#[derive(Debug, PartialEq)]
pub enum Change<'a> {
    /// Sets the value VALUE at location PATH
    Set(AugPath<'a>, Value<'a>),
    /// Sets multiple nodes (matching SUB relative to PATH) to VALUE
    SetM(AugPath<'a>, Sub<'a>, Value<'a>),
    /// Removes the node at location PATH
    Rm(AugPath<'a>),
    /// Synonym for Rm
    Remove(AugPath<'a>),
    /// Sets the node at PATH to NULL, creating it if needed
    Clear(AugPath<'a>),
    /// Sets multiple nodes (matching SUB relative to PATH) to NULL
    ClearM(AugPath<'a>, Sub<'a>),
    /// Creates PATH with the value NULL if it does not exist
    Touch(AugPath<'a>),
    /// Inserts an empty node LABEL either before or after PATH.
    Ins(Value<'a>, Value<'a>, AugPath<'a>),
    /// Synonym for Ins
    Insert(Value<'a>, Value<'a>, AugPath<'a>),
    /// Moves a node at PATH to the new location OTHER PATH
    Mv(AugPath<'a>, AugPath<'a>),
    /// Synonym for Mv
    Move(AugPath<'a>, AugPath<'a>),
    /// Rename a node at PATH to a new LABEL
    Rename(AugPath<'a>, Value<'a>),
    /// Sets Augeas variable $NAME to PATH
    DefVar(Value<'a>, AugPath<'a>),
    /// Sets Augeas variable $NAME to PATH, creating it with VALUE if needed
    DefNode(Value<'a>, AugPath<'a>, Value<'a>),
}

impl<'a> Change<'a> {
    fn evaluate(&self, context: Option<&str>, augeas: &'a mut Augeas) -> Result<()> {
        match self {
            Change::Set(path, value) => augeas.set(path.with_context(context).as_ref(), value)?,
            //Change::SetM(path, sub, value) => augeas.setm(path, sub, value).map(|_| ())?,
            //Change::Rm(path) => augeas.rm(path).map(|_| ())?,
            //Change::Remove(path) => augeas.rm(path).map(|_| ())?,
            //Change::Clear(path) => augeas.clear(path)?,
            //Change::ClearM(path, sub) => augeas.clearm(path, sub)?,
            //Change::Touch(path) => augeas.touch(path)?,
            //Change::Ins(label, position, path) => augeas.insert(label, position, path)?,
            //Change::Insert(label, position, path) => augeas.insert(label, position, path)?,
            //Change::Mv(path, other) => augeas.mv(path, other)?,
            //Change::Move(path, other) => augeas.mv(path, other)?,
            //Change::Rename(path, label) => augeas.rename(path, label).map(|_| ())?,
            //Change::DefVar(name, path) => augeas.defvar(name, path)?,
            //Change::DefNode(name, path, value) => augeas.defnode(name, path, value).map(|_| ())?,
            _ => todo!(),
        }
        Ok(())
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

/// Read a command name.
fn command(input: &str) -> IResult<&str, &str, VerboseError<&str>> {
    let (input, command) = alpha1(input)?;
    Ok((input, command))
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

fn cmd_set(input: &str) -> IResult<&str, Change, VerboseError<&str>> {
    let (input, _) = tag("set")(input)?;
    let (input, _) = space1(input)?;
    let (input, path) = arg(input)?;
    let (input, value) = arg(input)?;
    Ok((input, Change::Set(path.into(), value)))
}

fn cmd_setm(input: &str) -> IResult<&str, Change, VerboseError<&str>> {
    let (input, _) = tag("setm")(input)?;
    let (input, _) = space1(input)?;
    let (input, path) = arg(input)?;
    let (input, sub) = arg(input)?;
    let (input, value) = arg(input)?;
    Ok((input, Change::SetM(path.into(), sub, value)))
}

fn cmd_rm(input: &str) -> IResult<&str, Change, VerboseError<&str>> {
    let (input, _) = alt((tag("rm"), tag("remove")))(input)?;
    let (input, _) = space1(input)?;
    let (input, path) = arg(input)?;
    Ok((input, Change::Rm(path.into())))
}

fn cmd_clear(input: &str) -> IResult<&str, Change, VerboseError<&str>> {
    let (input, _) = tag("clear")(input)?;
    let (input, _) = space1(input)?;
    let (input, path) = arg(input)?;
    Ok((input, Change::Clear(path.into())))
}

fn cmd_clearm(input: &str) -> IResult<&str, Change, VerboseError<&str>> {
    let (input, _) = tag("clearm")(input)?;
    let (input, _) = space1(input)?;
    let (input, path) = arg(input)?;
    let (input, sub) = arg(input)?;
    Ok((input, Change::ClearM(path.into(), sub)))
}

fn cmd_touch(input: &str) -> IResult<&str, Change, VerboseError<&str>> {
    let (input, _) = tag("touch")(input)?;
    let (input, _) = space1(input)?;
    let (input, path) = arg(input)?;
    Ok((input, Change::Touch(path.into())))
}

fn cmd_ins(input: &str) -> IResult<&str, Change, VerboseError<&str>> {
    let (input, _) = alt((tag("ins"), tag("insert")))(input)?;
    let (input, _) = space1(input)?;
    let (input, label) = arg(input)?;
    let (input, position) = arg(input)?;
    let (input, path) = arg(input)?;
    Ok((input, Change::Ins(label, position, path.into())))
}

fn cmd_mv(input: &str) -> IResult<&str, Change, VerboseError<&str>> {
    let (input, _) = alt((tag("mv"), tag("move")))(input)?;
    let (input, _) = space1(input)?;
    let (input, path) = arg(input)?;
    let (input, other) = arg(input)?;
    Ok((input, Change::Mv(path.into(), other.into())))
}

fn cmd_rename(input: &str) -> IResult<&str, Change, VerboseError<&str>> {
    let (input, _) = tag("rename")(input)?;
    let (input, _) = space1(input)?;
    let (input, path) = arg(input)?;
    let (input, label) = arg(input)?;
    Ok((input, Change::Rename(path.into(), label)))
}

fn cmd_defvar(input: &str) -> IResult<&str, Change, VerboseError<&str>> {
    let (input, _) = tag("defvar")(input)?;
    let (input, _) = space1(input)?;
    let (input, name) = arg(input)?;
    let (input, path) = arg(input)?;
    Ok((input, Change::DefVar(name, path.into())))
}

fn cmd_defnode(input: &str) -> IResult<&str, Change, VerboseError<&str>> {
    let (input, _) = tag("defnode")(input)?;
    let (input, _) = space1(input)?;
    let (input, name) = arg(input)?;
    let (input, path) = arg(input)?;
    let (input, value) = arg(input)?;
    Ok((input, Change::DefNode(name, path.into(), value)))
}

/// Read a valid change.
fn change(input: &str) -> IResult<&str, Change, VerboseError<&str>> {
    alt((
        cmd_set,
        cmd_setm,
        cmd_rm,
        cmd_clear,
        cmd_clearm,
        cmd_touch,
        cmd_ins,
        cmd_mv,
        cmd_rename,
        cmd_defvar,
        cmd_defnode,
    ))(input)
}

fn line(input: &str) -> IResult<&str, Option<Change>, VerboseError<&str>> {
    let (input, _) = not(eof)(input)?;
    let (input, _) = space0(input)?;
    let (input, _) = opt(comment)(input)?;
    let (input, change) = opt(change)(input)?;
    let (input, _) = space0(input)?;
    let (input, _) = opt(comment)(input)?;
    let (input, _) = opt(line_ending)(input)?;
    Ok((input, change))
}

fn changes(input: &str) -> IResult<&str, Vec<Change>, VerboseError<&str>> {
    // many0 -> allow empty changes (only comments, etc.)
    let (input, changes) = many0(line)(input)?;
    let changes = changes.into_iter().filter_map(|c| c).collect();
    let (input, _) = multispace0(input)?;
    let (input, _) = eof(input)?;
    Ok((input, changes))
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
    fn test_command() {
        let input = "set /path/to/node value";
        let expected = "set";
        let result = command(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_command_with_multiple_words() {
        let input = "set /path/to/node value";
        let expected = "set";
        let result = command(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_command_with_no_space() {
        let input = "set";
        let expected = "set";
        let result = command(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_command_with_empty_input() {
        let input = "";
        let result = command(input);
        assert!(result.is_err());
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

    #[test]
    fn test_change_set() {
        let input = "set /path/to/node value";
        let expected = Change::Set("/path/to/node".into(), "value");
        let result = change(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_change_setm() {
        let input = "setm /path/to/nodes subnode value";
        let expected = Change::SetM("/path/to/nodes".into(), "subnode", "value");
        let result = change(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_change_rm() {
        let input = "rm /path/to/node";
        let expected = Change::Rm("/path/to/node".into());
        let result = change(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_change_clear() {
        let input = "clear /path/to/node";
        let expected = Change::Clear("/path/to/node".into());
        let result = change(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_change_clearm() {
        let input = "clearm /path/to/nodes subnode";
        let expected = Change::ClearM("/path/to/nodes".into(), "subnode");
        let result = change(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_change_touch() {
        let input = "touch /path/to/node";
        let expected = Change::Touch("/path/to/node".into());
        let result = change(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_change_ins() {
        let input = "ins label before /path/to/node";
        let expected = Change::Ins("label", "before", "/path/to/node".into());
        let result = change(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_change_mv() {
        let input = "mv /path/to/node /new/path";
        let expected = Change::Mv("/path/to/node".into(), "/new/path".into());
        let result = change(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_change_rename() {
        let input = "rename /path/to/node new_label";
        let expected = Change::Rename("/path/to/node".into(), "new_label");
        let result = change(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_change_defvar() {
        let input = "defvar name /path/to/node";
        let expected = Change::DefVar("name", "/path/to/node".into());
        let result = change(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_change_defnode() {
        let input = "defnode name /path/to/node value";
        let expected = Change::DefNode("name", "/path/to/node".into(), "value");
        let result = change(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_empty_line() {
        let input = "";
        let result = line(input);
        assert!(result.is_err());
    }

    #[test]
    fn test_empty_line_break() {
        let input = "\n";
        let result = line(input).unwrap();
        assert_eq!(result.1, None);
    }

    #[test]
    fn test_empty_line_carriage_return() {
        let input = "\r\n";
        let result = line(input).unwrap();
        assert_eq!(result.1, None);
    }

    #[test]
    fn test_line() {
        let input = "set /path/to/node value\n";
        let expected = Change::Set("/path/to/node".into(), "value");
        let result = line(input).unwrap();
        assert_eq!(result.1, Some(expected));
    }

    #[test]
    fn test_line_command_and_comment() {
        let input = "set /path/to/node value # comment\n";
        let expected = Change::Set("/path/to/node".into(), "value");
        let result = line(input).unwrap();
        assert_eq!(result.1, Some(expected));
    }

    #[test]
    fn test_line_comment() {
        let input = "# comment\n";
        let result = line(input).unwrap();
        assert_eq!(result.1, None);
    }

    #[test]
    fn test_changes() {
        let input = r#"
            # This is a comment
            set /path/to/node value
            setm /path/to/nodes 'sub node' value
            rm /path/to/node
            # other comment

            clear /path/to/node # another command
            clearm /path/to/nodes "sub node"
            touch /path/to/node

            ins  label  before        /path/to/node
            mv /path/to/node /new/path
            rename /path/to/node new_label
            defvar name /path/to/node
            defnode name /path/to/node value
        "#;
        let expected = vec![
            Change::Set("/path/to/node".into(), "value"),
            Change::SetM("/path/to/nodes".into(), "sub node", "value"),
            Change::Rm("/path/to/node".into()),
            Change::Clear("/path/to/node".into()),
            Change::ClearM("/path/to/nodes".into(), "sub node"),
            Change::Touch("/path/to/node".into()),
            Change::Ins("label", "before", "/path/to/node".into()),
            Change::Mv("/path/to/node".into(), "/new/path".into()),
            Change::Rename("/path/to/node".into(), "new_label"),
            Change::DefVar("name", "/path/to/node".into()),
            Change::DefNode("name", "/path/to/node".into(), "value"),
        ];
        let result = changes(input).unwrap();
        assert_eq!(result.1, expected);
    }
}
