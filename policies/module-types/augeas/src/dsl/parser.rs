// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use crate::dsl;
use crate::dsl::script::Expression;
use nom::branch::alt;
use nom::bytes::complete::tag;
use nom::character::complete::{line_ending, multispace0, space0, space1};
use nom::combinator::{eof, map_res, not, opt};
use nom::error::VerboseError;
use nom::multi::many0;
use nom::IResult;

fn cmd_set(input: &str) -> IResult<&str, Expression, VerboseError<&str>> {
    let (input, _) = tag("set")(input)?;
    let (input, _) = space1(input)?;
    let (input, path) = dsl::arg(input)?;
    let (input, value) = dsl::arg(input)?;
    Ok((input, Expression::Set(path.into(), value)))
}

fn cmd_setm(input: &str) -> IResult<&str, Expression, VerboseError<&str>> {
    let (input, _) = tag("setm")(input)?;
    let (input, _) = space1(input)?;
    let (input, path) = dsl::arg(input)?;
    let (input, sub) = dsl::arg(input)?;
    let (input, value) = dsl::arg(input)?;
    Ok((input, Expression::SetMultiple(path.into(), sub, value)))
}

fn cmd_rm(input: &str) -> IResult<&str, Expression, VerboseError<&str>> {
    let (input, _) = alt((tag("rm"), tag("remove")))(input)?;
    let (input, _) = space1(input)?;
    let (input, path) = dsl::arg(input)?;
    Ok((input, Expression::Remove(path.into())))
}

fn cmd_clear(input: &str) -> IResult<&str, Expression, VerboseError<&str>> {
    let (input, _) = tag("clear")(input)?;
    let (input, _) = space1(input)?;
    let (input, path) = dsl::arg(input)?;
    Ok((input, Expression::Clear(path.into())))
}

fn cmd_clearm(input: &str) -> IResult<&str, Expression, VerboseError<&str>> {
    let (input, _) = tag("clearm")(input)?;
    let (input, _) = space1(input)?;
    let (input, path) = dsl::arg(input)?;
    let (input, sub) = dsl::arg(input)?;
    Ok((input, Expression::ClearMultiple(path.into(), sub)))
}

fn cmd_touch(input: &str) -> IResult<&str, Expression, VerboseError<&str>> {
    let (input, _) = tag("touch")(input)?;
    let (input, _) = space1(input)?;
    let (input, path) = dsl::arg(input)?;
    Ok((input, Expression::Touch(path.into())))
}

fn cmd_ins(input: &str) -> IResult<&str, Expression, VerboseError<&str>> {
    let (input, _) = alt((tag("ins"), tag("insert")))(input)?;
    let (input, _) = space1(input)?;
    let (input, label) = dsl::arg(input)?;
    let (input, position) = map_res(dsl::arg, |p| p.parse())(input)?;
    let (input, path) = dsl::arg(input)?;
    Ok((input, Expression::Insert(label, position, path.into())))
}

fn cmd_mv(input: &str) -> IResult<&str, Expression, VerboseError<&str>> {
    let (input, _) = alt((tag("mv"), tag("move")))(input)?;
    let (input, _) = space1(input)?;
    let (input, path) = dsl::arg(input)?;
    let (input, other) = dsl::arg(input)?;
    Ok((input, Expression::Move(path.into(), other.into())))
}

fn cmd_rename(input: &str) -> IResult<&str, Expression, VerboseError<&str>> {
    let (input, _) = tag("rename")(input)?;
    let (input, _) = space1(input)?;
    let (input, path) = dsl::arg(input)?;
    let (input, label) = dsl::arg(input)?;
    Ok((input, Expression::Rename(path.into(), label)))
}

fn cmd_defvar(input: &str) -> IResult<&str, Expression, VerboseError<&str>> {
    let (input, _) = tag("defvar")(input)?;
    let (input, _) = space1(input)?;
    let (input, name) = dsl::arg(input)?;
    let (input, path) = dsl::arg(input)?;
    Ok((input, Expression::DefineVar(name, path.into())))
}

fn cmd_defnode(input: &str) -> IResult<&str, Expression, VerboseError<&str>> {
    let (input, _) = tag("defnode")(input)?;
    let (input, _) = space1(input)?;
    let (input, name) = dsl::arg(input)?;
    let (input, path) = dsl::arg(input)?;
    let (input, value) = dsl::arg(input)?;
    Ok((input, Expression::DefineNode(name, path.into(), value)))
}

/// Read a valid change.
fn change(input: &str) -> IResult<&str, Expression, VerboseError<&str>> {
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

fn line(input: &str) -> IResult<&str, Option<Expression>, VerboseError<&str>> {
    let (input, _) = not(eof)(input)?;
    let (input, _) = space0(input)?;
    let (input, _) = opt(dsl::comment)(input)?;
    let (input, change) = opt(change)(input)?;
    let (input, _) = space0(input)?;
    let (input, _) = opt(dsl::comment)(input)?;
    let (input, _) = opt(line_ending)(input)?;
    Ok((input, change))
}

pub fn changes(input: &str) -> IResult<&str, Vec<Expression>, VerboseError<&str>> {
    // many0 -> allow empty changes (only comments, etc.)
    let (input, changes) = many0(line)(input)?;
    let changes = changes.into_iter().flatten().collect();
    let (input, _) = multispace0(input)?;
    let (input, _) = eof(input)?;
    Ok((input, changes))
}

#[cfg(test)]
mod tests {
    use crate::dsl::parser::{change, changes, line};
    use crate::dsl::script::*;
    use raugeas::Position;

    #[test]
    fn test_change_set() {
        let input = "set /path/to/node value";
        let expected = Expression::Set("/path/to/node".into(), "value");
        let result = change(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_change_setm() {
        let input = "setm /path/to/nodes subnode value";
        let expected = Expression::SetMultiple("/path/to/nodes".into(), "subnode", "value");
        let result = change(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_change_rm() {
        let input = "rm /path/to/node";
        let expected = Expression::Remove("/path/to/node".into());
        let result = change(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_change_clear() {
        let input = "clear /path/to/node";
        let expected = Expression::Clear("/path/to/node".into());
        let result = change(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_change_clearm() {
        let input = "clearm /path/to/nodes subnode";
        let expected = Expression::ClearMultiple("/path/to/nodes".into(), "subnode");
        let result = change(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_change_touch() {
        let input = "touch /path/to/node";
        let expected = Expression::Touch("/path/to/node".into());
        let result = change(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_change_ins() {
        let input = "ins label before /path/to/node";
        let expected = Expression::Insert("label", Position::Before, "/path/to/node".into());
        let result = change(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_change_mv() {
        let input = "mv /path/to/node /new/path";
        let expected = Expression::Move("/path/to/node".into(), "/new/path".into());
        let result = change(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_change_rename() {
        let input = "rename /path/to/node new_label";
        let expected = Expression::Rename("/path/to/node".into(), "new_label");
        let result = change(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_change_defvar() {
        let input = "defvar name /path/to/node";
        let expected = Expression::DefineVar("name", "/path/to/node".into());
        let result = change(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_change_defnode() {
        let input = "defnode name /path/to/node value";
        let expected = Expression::DefineNode("name", "/path/to/node".into(), "value");
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
        let expected = Expression::Set("/path/to/node".into(), "value");
        let result = line(input).unwrap();
        assert_eq!(result.1, Some(expected));
    }

    #[test]
    fn test_line_command_and_comment() {
        let input = "set /path/to/node value # comment\n";
        let expected = Expression::Set("/path/to/node".into(), "value");
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
    fn test_script_parser() {
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

            quit
        "#;
        let expected = vec![
            Expression::Set("/path/to/node".into(), "value"),
            Expression::SetMultiple("/path/to/nodes".into(), "sub node", "value"),
            Expression::Remove("/path/to/node".into()),
            Expression::Clear("/path/to/node".into()),
            Expression::ClearMultiple("/path/to/nodes".into(), "sub node"),
            Expression::Touch("/path/to/node".into()),
            Expression::Insert("label", Position::Before, "/path/to/node".into()),
            Expression::Move("/path/to/node".into(), "/new/path".into()),
            Expression::Rename("/path/to/node".into(), "new_label"),
            Expression::DefineVar("name", "/path/to/node".into()),
            Expression::DefineNode("name", "/path/to/node".into(), "value"),
            Expression::Quit,
        ];
        let result = changes(input).unwrap();
        assert_eq!(result.1, expected);
    }
}
