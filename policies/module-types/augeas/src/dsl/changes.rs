// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use nom::branch::alt;
use nom::bytes::complete::tag;
use nom::character::complete::{line_ending, multispace0, space0, space1};
use nom::combinator::{eof, map_res, not, opt};
use nom::multi::many0;
use nom::{Finish, IResult};

use crate::dsl;
use crate::dsl::{AugPath, Sub, Value};
use anyhow::{anyhow, Result};
use nom::error::VerboseError;
use raugeas::{Augeas, Position};
use rudder_module_type::rudder_debug;

/// The ordered list of changes to apply.
///
/// FIXME: *excellent* reporting for each change.
/// FIXME: make it as close as possible to the augeas command line & srun syntax.
#[derive(Debug, PartialEq)]
pub struct Changes<'a> {
    changes: Vec<Change<'a>>,
}

impl<'a> Changes<'a> {
    pub fn from_str(input: &'a str) -> Result<Changes<'a>> {
        let (_, changes) = changes(input)
            .finish()
            // We can't keep the verbose error as it contains references to the input.
            .map_err(|e| anyhow!(format!("{:?}", e)))?;
        Ok(Changes { changes })
    }

    pub fn run(&self, context: Option<&str>, augeas: &mut Augeas) -> Result<()> {
        rudder_debug!("Running {} changes", self.changes.len());
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
    SetMultiple(AugPath<'a>, Sub<'a>, Value<'a>),
    /// Removes the node at location PATH
    Remove(AugPath<'a>),
    /// Sets the node at PATH to NULL, creating it if needed
    Clear(AugPath<'a>),
    /// Sets multiple nodes (matching SUB relative to PATH) to NULL
    ClearMultiple(AugPath<'a>, Sub<'a>),
    /// Creates PATH with the value NULL if it does not exist
    Touch(AugPath<'a>),
    /// Inserts an empty node LABEL either before or after PATH.
    Insert(Value<'a>, Position, AugPath<'a>),
    /// Moves a node at PATH to the new location OTHER PATH
    Move(AugPath<'a>, AugPath<'a>),
    /// Rename a node at PATH to a new LABEL
    Rename(AugPath<'a>, Value<'a>),
    /// Sets Augeas variable $NAME to PATH
    DefineVar(Value<'a>, AugPath<'a>),
    /// Sets Augeas variable $NAME to PATH, creating it with VALUE if needed
    DefineNode(Value<'a>, AugPath<'a>, Value<'a>),
}

impl<'a> Change<'a> {
    fn evaluate(&self, context: Option<&str>, augeas: &'a mut Augeas) -> Result<()> {
        match self {
            Change::Set(path, value) => augeas.set(path.with_context(context).as_ref(), value)?,
            Change::SetMultiple(path, sub, value) => {
                let n = augeas.setm(path.with_context(context).as_ref(), sub, value)?;
                rudder_debug!("setm: modified {n} nodes");
            }
            Change::Remove(path) => {
                let n = augeas.rm(path.with_context(context).as_ref())?;
                rudder_debug!("rm: removed {n} nodes");
            }
            Change::Clear(path) => augeas.clear(path.with_context(context).as_ref())?,
            Change::ClearMultiple(path, sub) => {
                let n = augeas.clearm(path.with_context(context).as_ref(), sub)?;
                rudder_debug!("clearm: cleared {n} nodes");
            }
            Change::Touch(path) => augeas.touch(path.with_context(context).as_ref())?,
            Change::Insert(label, position, path) => {
                augeas.insert(path.with_context(context).as_ref(), label, *position)?
            }
            Change::Move(path, other) => augeas.mv(
                path.with_context(context).as_ref(),
                other.with_context(context).as_ref(),
            )?,
            Change::Rename(path, label) => {
                let n = augeas.rename(path.with_context(context).as_ref(), label)?;
                rudder_debug!("rename: renamed {n} nodes");
            }
            Change::DefineVar(name, path) => {
                augeas.defvar(name, path.with_context(context).as_ref())?
            }
            Change::DefineNode(name, path, value) => {
                let created = augeas.defnode(name, path.with_context(context).as_ref(), value)?;
                if created {
                    rudder_debug!("defnode: 1 node was created");
                } else {
                    rudder_debug!("defnode: no nodes were created");
                }
            }
        }
        Ok(())
    }
}

fn cmd_set(input: &str) -> IResult<&str, Change, VerboseError<&str>> {
    let (input, _) = tag("set")(input)?;
    let (input, _) = space1(input)?;
    let (input, path) = dsl::arg(input)?;
    let (input, value) = dsl::arg(input)?;
    Ok((input, Change::Set(path.into(), value)))
}

fn cmd_setm(input: &str) -> IResult<&str, Change, VerboseError<&str>> {
    let (input, _) = tag("setm")(input)?;
    let (input, _) = space1(input)?;
    let (input, path) = dsl::arg(input)?;
    let (input, sub) = dsl::arg(input)?;
    let (input, value) = dsl::arg(input)?;
    Ok((input, Change::SetMultiple(path.into(), sub, value)))
}

fn cmd_rm(input: &str) -> IResult<&str, Change, VerboseError<&str>> {
    let (input, _) = alt((tag("rm"), tag("remove")))(input)?;
    let (input, _) = space1(input)?;
    let (input, path) = dsl::arg(input)?;
    Ok((input, Change::Remove(path.into())))
}

fn cmd_clear(input: &str) -> IResult<&str, Change, VerboseError<&str>> {
    let (input, _) = tag("clear")(input)?;
    let (input, _) = space1(input)?;
    let (input, path) = dsl::arg(input)?;
    Ok((input, Change::Clear(path.into())))
}

fn cmd_clearm(input: &str) -> IResult<&str, Change, VerboseError<&str>> {
    let (input, _) = tag("clearm")(input)?;
    let (input, _) = space1(input)?;
    let (input, path) = dsl::arg(input)?;
    let (input, sub) = dsl::arg(input)?;
    Ok((input, Change::ClearMultiple(path.into(), sub)))
}

fn cmd_touch(input: &str) -> IResult<&str, Change, VerboseError<&str>> {
    let (input, _) = tag("touch")(input)?;
    let (input, _) = space1(input)?;
    let (input, path) = dsl::arg(input)?;
    Ok((input, Change::Touch(path.into())))
}

fn cmd_ins(input: &str) -> IResult<&str, Change, VerboseError<&str>> {
    let (input, _) = alt((tag("ins"), tag("insert")))(input)?;
    let (input, _) = space1(input)?;
    let (input, label) = dsl::arg(input)?;
    let (input, position) = map_res(dsl::arg, |p| p.parse())(input)?;
    let (input, path) = dsl::arg(input)?;
    Ok((input, Change::Insert(label, position, path.into())))
}

fn cmd_mv(input: &str) -> IResult<&str, Change, VerboseError<&str>> {
    let (input, _) = alt((tag("mv"), tag("move")))(input)?;
    let (input, _) = space1(input)?;
    let (input, path) = dsl::arg(input)?;
    let (input, other) = dsl::arg(input)?;
    Ok((input, Change::Move(path.into(), other.into())))
}

fn cmd_rename(input: &str) -> IResult<&str, Change, VerboseError<&str>> {
    let (input, _) = tag("rename")(input)?;
    let (input, _) = space1(input)?;
    let (input, path) = dsl::arg(input)?;
    let (input, label) = dsl::arg(input)?;
    Ok((input, Change::Rename(path.into(), label)))
}

fn cmd_defvar(input: &str) -> IResult<&str, Change, VerboseError<&str>> {
    let (input, _) = tag("defvar")(input)?;
    let (input, _) = space1(input)?;
    let (input, name) = dsl::arg(input)?;
    let (input, path) = dsl::arg(input)?;
    Ok((input, Change::DefineVar(name, path.into())))
}

fn cmd_defnode(input: &str) -> IResult<&str, Change, VerboseError<&str>> {
    let (input, _) = tag("defnode")(input)?;
    let (input, _) = space1(input)?;
    let (input, name) = dsl::arg(input)?;
    let (input, path) = dsl::arg(input)?;
    let (input, value) = dsl::arg(input)?;
    Ok((input, Change::DefineNode(name, path.into(), value)))
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
    let (input, _) = opt(dsl::comment)(input)?;
    let (input, change) = opt(change)(input)?;
    let (input, _) = space0(input)?;
    let (input, _) = opt(dsl::comment)(input)?;
    let (input, _) = opt(line_ending)(input)?;
    Ok((input, change))
}

fn changes(input: &str) -> IResult<&str, Vec<Change>, VerboseError<&str>> {
    // many0 -> allow empty changes (only comments, etc.)
    let (input, changes) = many0(line)(input)?;
    let changes = changes.into_iter().flatten().collect();
    let (input, _) = multispace0(input)?;
    let (input, _) = eof(input)?;
    Ok((input, changes))
}

#[cfg(test)]
mod tests {
    use super::*;

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
        let expected = Change::SetMultiple("/path/to/nodes".into(), "subnode", "value");
        let result = change(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_change_rm() {
        let input = "rm /path/to/node";
        let expected = Change::Remove("/path/to/node".into());
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
        let expected = Change::ClearMultiple("/path/to/nodes".into(), "subnode");
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
        let expected = Change::Insert("label", Position::Before, "/path/to/node".into());
        let result = change(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_change_mv() {
        let input = "mv /path/to/node /new/path";
        let expected = Change::Move("/path/to/node".into(), "/new/path".into());
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
        let expected = Change::DefineVar("name", "/path/to/node".into());
        let result = change(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_change_defnode() {
        let input = "defnode name /path/to/node value";
        let expected = Change::DefineNode("name", "/path/to/node".into(), "value");
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
            Change::SetMultiple("/path/to/nodes".into(), "sub node", "value"),
            Change::Remove("/path/to/node".into()),
            Change::Clear("/path/to/node".into()),
            Change::ClearMultiple("/path/to/nodes".into(), "sub node"),
            Change::Touch("/path/to/node".into()),
            Change::Insert("label", Position::Before, "/path/to/node".into()),
            Change::Move("/path/to/node".into(), "/new/path".into()),
            Change::Rename("/path/to/node".into(), "new_label"),
            Change::DefineVar("name", "/path/to/node".into()),
            Change::DefineNode("name", "/path/to/node".into(), "value"),
        ];
        let result = changes(input).unwrap();
        assert_eq!(result.1, expected);
    }
}
