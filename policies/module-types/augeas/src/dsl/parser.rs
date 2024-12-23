// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use crate::dsl::script::Expression;
use nom::branch::alt;
use nom::bytes::complete::{is_not, tag};
use nom::character::complete::{
    alpha1, char, line_ending, multispace0, not_line_ending, space0, space1,
};
use nom::combinator::{eof, map_res, not, opt};
use nom::error::VerboseError;
use nom::multi::{many0, separated_list0};
use nom::sequence::delimited;
use nom::IResult;

/// Read a comment.
///
/// A comment starts with a `#` and ends with a newline.
pub fn comment(input: &str) -> IResult<&str, &str, VerboseError<&str>> {
    let (input, _) = tag("#")(input)?;
    let (input, comment) = not_line_ending(input)?;
    Ok((input, comment))
}

/// Read an array of arguments.
pub fn arg_array(input: &str) -> IResult<&str, Vec<&str>, VerboseError<&str>> {
    let (input, _) = space0(input)?;
    let (input, _) = char('[')(input)?;
    let (input, args) = separated_list0(delimited(space0, char(','), space0), arg)(input)?;
    let (input, _) = space0(input)?;
    let (input, _) = char(']')(input)?;
    Ok((input, args))
}

/// Read a path or a value.
///
/// It can contain spaces, in which case it must be quoted.
pub fn arg(input: &str) -> IResult<&str, &str, VerboseError<&str>> {
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

fn cmd(input: &str) -> IResult<&str, &str, VerboseError<&str>> {
    let (input, cmd) = alpha1(input)?;
    let (input, _) = space1(input)?;
    Ok((input, cmd))
}

fn cmd_set(input: &str) -> IResult<&str, Expression, VerboseError<&str>> {
    let (input, path) = arg(input)?;
    let (input, value) = arg(input)?;
    Ok((input, Expression::Set(path.into(), value)))
}

fn cmd_setm(input: &str) -> IResult<&str, Expression, VerboseError<&str>> {
    let (input, path) = arg(input)?;
    let (input, sub) = arg(input)?;
    let (input, value) = arg(input)?;
    Ok((input, Expression::SetMultiple(path.into(), sub, value)))
}

fn cmd_rm(input: &str) -> IResult<&str, Expression, VerboseError<&str>> {
    let (input, path) = arg(input)?;
    Ok((input, Expression::Remove(path.into())))
}

fn cmd_clear(input: &str) -> IResult<&str, Expression, VerboseError<&str>> {
    let (input, path) = arg(input)?;
    Ok((input, Expression::Clear(path.into())))
}

fn cmd_clearm(input: &str) -> IResult<&str, Expression, VerboseError<&str>> {
    let (input, path) = arg(input)?;
    let (input, sub) = arg(input)?;
    Ok((input, Expression::ClearMultiple(path.into(), sub)))
}

fn cmd_touch(input: &str) -> IResult<&str, Expression, VerboseError<&str>> {
    let (input, path) = arg(input)?;
    Ok((input, Expression::Touch(path.into())))
}

fn cmd_ins(input: &str) -> IResult<&str, Expression, VerboseError<&str>> {
    let (input, label) = arg(input)?;
    let (input, position) = map_res(arg, |p| p.parse())(input)?;
    let (input, path) = arg(input)?;
    Ok((input, Expression::Insert(label, position, path.into())))
}

fn cmd_mv(input: &str) -> IResult<&str, Expression, VerboseError<&str>> {
    let (input, path) = arg(input)?;
    let (input, other) = arg(input)?;
    Ok((input, Expression::Move(path.into(), other.into())))
}

fn cmd_rename(input: &str) -> IResult<&str, Expression, VerboseError<&str>> {
    let (input, path) = arg(input)?;
    let (input, label) = arg(input)?;
    Ok((input, Expression::Rename(path.into(), label)))
}

fn cmd_defvar(input: &str) -> IResult<&str, Expression, VerboseError<&str>> {
    let (input, name) = arg(input)?;
    let (input, path) = arg(input)?;
    Ok((input, Expression::DefineVar(name, path.into())))
}

fn cmd_defnode(input: &str) -> IResult<&str, Expression, VerboseError<&str>> {
    let (input, name) = arg(input)?;
    let (input, path) = arg(input)?;
    let (input, value) = arg(input)?;
    Ok((input, Expression::DefineNode(name, path.into(), value)))
}

fn cmd_match_include(input: &str) -> IResult<&str, Expression, VerboseError<&str>> {
    let (input, path) = arg(input)?;
    let (input, _) = tag("include")(input)?;
    let (input, value) = arg(input)?;
    Ok((input, Expression::MatchInclude(path.into(), value)))
}

fn cmd_match_not_include(input: &str) -> IResult<&str, Expression, VerboseError<&str>> {
    let (input, path) = arg(input)?;
    let (input, _) = tag("not_include")(input)?;
    let (input, value) = arg(input)?;
    Ok((input, Expression::MatchNotInclude(path.into(), value)))
}

fn cmd_match_equal(input: &str) -> IResult<&str, Expression, VerboseError<&str>> {
    let (input, path) = arg(input)?;
    let (input, _) = tag("==")(input)?;
    let (input, value) = arg_array(input)?;
    Ok((input, Expression::MatchEqual(path.into(), value)))
}

fn cmd_match_not_equal(input: &str) -> IResult<&str, Expression, VerboseError<&str>> {
    let (input, path) = arg(input)?;
    let (input, _) = tag("!=")(input)?;
    let (input, value) = arg_array(input)?;
    Ok((input, Expression::MatchNotEqual(path.into(), value)))
}

fn cmd_generic(input: &str) -> IResult<&str, Expression, VerboseError<&str>> {
    let (input, cmd) = not_line_ending(input)?;
    Ok((input, Expression::GenericAugeas(cmd)))
}

/// Read a valid change.
fn expression(input: &str) -> IResult<&str, Expression, VerboseError<&str>> {
    let (i, c) = cmd(input)?;
    match c {
        "set" => cmd_set(i),
        "setm" => cmd_setm(i),
        "rm" | "remove" => cmd_rm(i),
        "clear" => cmd_clear(i),
        "clearm" => cmd_clearm(i),
        "touch" => cmd_touch(i),
        "ins" | "insert" => cmd_ins(i),
        "mv" | "move" => cmd_mv(i),
        "rename" => cmd_rename(i),
        "defvar" => cmd_defvar(i),
        "defnode" => cmd_defnode(i),
        "match" => alt((cmd_match_include, cmd_match_not_include))(i),
        "save" => Ok((i, Expression::Save)),
        "quit" => Ok((i, Expression::Quit)),
        "load" => Ok((i, Expression::Load)),
        _ => cmd_generic(input),
    }
}

fn line(input: &str) -> IResult<&str, Option<Expression>, VerboseError<&str>> {
    let (input, _) = not(eof)(input)?;
    let (input, _) = space0(input)?;
    let (input, _) = opt(comment)(input)?;
    let (input, e) = opt(expression)(input)?;
    let (input, _) = space0(input)?;
    let (input, _) = opt(comment)(input)?;
    let (input, _) = opt(line_ending)(input)?;
    Ok((input, e))
}

pub fn script(input: &str) -> IResult<&str, Vec<Expression>, VerboseError<&str>> {
    // many0 -> allow empty changes (only comments, etc.)
    let (input, changes) = many0(line)(input)?;
    let changes = changes.into_iter().flatten().collect();
    let (input, _) = multispace0(input)?;
    let (input, _) = eof(input)?;
    Ok((input, changes))
}

#[cfg(test)]
mod tests {
    use crate::dsl::parser::{arg_array, expression, line, script};
    use crate::dsl::script::*;
    use raugeas::Position;

    fn test_match_include() {
        let input = "match /files/etc include token";
        let expected = Expression::MatchInclude("/files/etc".into(), "token");
        let result = expression(input).unwrap();
        assert_eq!(result.1, expected);
    }

    fn test_match_not_include() {
        let input = "match /files/etc not_include token";
        let expected = Expression::MatchNotInclude("/files/etc".into(), "token");
        let result = expression(input).unwrap();
        assert_eq!(result.1, expected);
    }

    fn test_arg_array() {
        let input = "[arg1, 'arg2', \"arg3\"]";
        let expected = vec!["arg1", "arg2", "arg3"];
        let result = arg_array(input).unwrap();
        assert_eq!(result.1, expected);
    }

    fn test_cmd_generic() {
        let input = "generic command";
        let expected = Expression::GenericAugeas("generic command");
        let result = expression(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_change_set() {
        let input = "set /path/to/node value";
        let expected = Expression::Set("/path/to/node".into(), "value");
        let result = expression(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_change_setm() {
        let input = "setm /path/to/nodes subnode value";
        let expected = Expression::SetMultiple("/path/to/nodes".into(), "subnode", "value");
        let result = expression(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_change_rm() {
        let input = "rm /path/to/node";
        let expected = Expression::Remove("/path/to/node".into());
        let result = expression(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_change_clear() {
        let input = "clear /path/to/node";
        let expected = Expression::Clear("/path/to/node".into());
        let result = expression(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_change_clearm() {
        let input = "clearm /path/to/nodes subnode";
        let expected = Expression::ClearMultiple("/path/to/nodes".into(), "subnode");
        let result = expression(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_change_touch() {
        let input = "touch /path/to/node";
        let expected = Expression::Touch("/path/to/node".into());
        let result = expression(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_change_ins() {
        let input = "ins label before /path/to/node";
        let expected = Expression::Insert("label", Position::Before, "/path/to/node".into());
        let result = expression(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_change_mv() {
        let input = "mv /path/to/node /new/path";
        let expected = Expression::Move("/path/to/node".into(), "/new/path".into());
        let result = expression(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_change_rename() {
        let input = "rename /path/to/node new_label";
        let expected = Expression::Rename("/path/to/node".into(), "new_label");
        let result = expression(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_change_defvar() {
        let input = "defvar name /path/to/node";
        let expected = Expression::DefineVar("name", "/path/to/node".into());
        let result = expression(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_change_defnode() {
        let input = "defnode name /path/to/node value";
        let expected = Expression::DefineNode("name", "/path/to/node".into(), "value");
        let result = expression(input).unwrap();
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
        let input = " comment\n";
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
        ];
        let result = script(input).unwrap();
        assert_eq!(result.1, expected);
    }
}
