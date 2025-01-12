// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use crate::dsl::script::Expr;
use crate::dsl::value_type::ValueType;
use nom::branch::alt;
use nom::bytes::complete::{escaped, is_not, tag};
use nom::character::complete::{
    alpha1, alphanumeric1, char, line_ending, multispace0, not_line_ending, one_of, space0,
};
use nom::combinator::{cut, eof, map_res, not, opt};
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

// static const char *const escape_chars = "\a\b\t\n\v\f\r";

/// Read a path or a value.
///
/// It can contain spaces, in which case it must be quoted, either with single or double quotes.
// FIXME: handle escaped quotes
pub fn arg(input: &str) -> IResult<&str, &str, VerboseError<&str>> {
    let (input, _) = space0(input)?;
    // either a quoted string or an unquoted string
    let (input, arg) = alt((
        // FIXME cleanup eol & delimiters
        delimited(char('"'), is_not("\"\r\n"), char('"')),
        delimited(char('\''), is_not("'\r\n"), char('\'')),
        is_not("\"' \t\r\n"),
    ))(input)?;
    let (input, _) = space0(input)?;
    Ok((input, arg))
}

/// Read a command, same as an argument but only alphanumeric characters.
fn cmd(input: &str) -> IResult<&str, &str, VerboseError<&str>> {
    let (input, cmd) = alpha1(input)?;
    let (input, _) = space0(input)?;
    Ok((input, cmd))
}

fn cmd_set(input: &str) -> IResult<&str, Expr, VerboseError<&str>> {
    let (input, path) = arg(input)?;
    let (input, value) = arg(input)?;
    Ok((input, Expr::Set(path.into(), value)))
}

fn cmd_setm(input: &str) -> IResult<&str, Expr, VerboseError<&str>> {
    let (input, path) = arg(input)?;
    let (input, sub) = arg(input)?;
    let (input, value) = arg(input)?;
    Ok((input, Expr::SetMultiple(path.into(), sub, value)))
}

fn cmd_rm(input: &str) -> IResult<&str, Expr, VerboseError<&str>> {
    let (input, path) = arg(input)?;
    Ok((input, Expr::Remove(path.into())))
}

fn cmd_clear(input: &str) -> IResult<&str, Expr, VerboseError<&str>> {
    let (input, path) = arg(input)?;
    Ok((input, Expr::Clear(path.into())))
}

fn cmd_clearm(input: &str) -> IResult<&str, Expr, VerboseError<&str>> {
    let (input, path) = arg(input)?;
    let (input, sub) = arg(input)?;
    Ok((input, Expr::ClearMultiple(path.into(), sub)))
}

fn cmd_touch(input: &str) -> IResult<&str, Expr, VerboseError<&str>> {
    let (input, path) = arg(input)?;
    Ok((input, Expr::Touch(path.into())))
}

fn cmd_ins(input: &str) -> IResult<&str, Expr, VerboseError<&str>> {
    let (input, label) = arg(input)?;
    let (input, position) = map_res(arg, |p| p.parse())(input)?;
    let (input, path) = arg(input)?;
    Ok((input, Expr::Insert(label, position, path.into())))
}

fn cmd_mv(input: &str) -> IResult<&str, Expr, VerboseError<&str>> {
    let (input, path) = arg(input)?;
    let (input, other) = arg(input)?;
    Ok((input, Expr::Move(path.into(), other.into())))
}

fn cmd_rename(input: &str) -> IResult<&str, Expr, VerboseError<&str>> {
    let (input, path) = arg(input)?;
    let (input, label) = arg(input)?;
    Ok((input, Expr::Rename(path.into(), label)))
}

fn cmd_defvar(input: &str) -> IResult<&str, Expr, VerboseError<&str>> {
    let (input, name) = arg(input)?;
    let (input, path) = arg(input)?;
    Ok((input, Expr::DefineVar(name, path.into())))
}

fn cmd_defnode(input: &str) -> IResult<&str, Expr, VerboseError<&str>> {
    let (input, name) = arg(input)?;
    let (input, path) = arg(input)?;
    let (input, value) = arg(input)?;
    Ok((input, Expr::DefineNode(name, path.into(), value)))
}

fn cmd_is_type(input: &str) -> IResult<&str, Expr, VerboseError<&str>> {
    let (input, value_type) = map_res(arg, |p| p.parse::<ValueType>())(input)?;
    let (input, path) = arg(input)?;

    Ok((input, Expr::HasType(path.into(), value_type)))
}

fn cmd_match(input: &str) -> IResult<&str, Expr, VerboseError<&str>> {
    let (input, path) = arg(input)?;
    let (input, sub_cmd) = arg(input)?;

    match sub_cmd {
        "include" => {
            let (input, value) = arg(input)?;
            Ok((input, Expr::MatchInclude(path.into(), value)))
        }
        "not_include" => {
            let (input, value) = arg(input)?;
            Ok((input, Expr::MatchNotInclude(path.into(), value)))
        }
        "==" => {
            let (input, value) = arg_array(input)?;
            Ok((input, Expr::MatchEqual(path.into(), value)))
        }
        "!=" => {
            let (input, value) = arg_array(input)?;
            Ok((input, Expr::MatchNotEqual(path.into(), value)))
        }
        "len" => {
            let (input, cmp) = map_res(arg, |p| p.parse())(input)?;
            let (input, i) = map_res(arg, |p| p.parse::<usize>())(input)?;
            Ok((input, Expr::MatchSize(path.into(), cmp, i)))
        }
        _ => todo!(),
    }
}
fn cmd_values(input: &str) -> IResult<&str, Expr, VerboseError<&str>> {
    let (input, path) = arg(input)?;
    let (input, sub_cmd) = arg(input)?;

    match sub_cmd {
        "include" => {
            let (input, value) = arg(input)?;
            Ok((input, Expr::ValuesInclude(path.into(), value)))
        }
        "not_include" => {
            let (input, value) = arg(input)?;
            Ok((input, Expr::ValuesNotInclude(path.into(), value)))
        }
        "==" => {
            let (input, value) = arg_array(input)?;
            Ok((input, Expr::ValuesEqual(path.into(), value)))
        }
        "!=" => {
            let (input, value) = arg_array(input)?;
            Ok((input, Expr::ValuesNotEqual(path.into(), value)))
        }
        _ => todo!(),
    }
}

fn cmd_generic(input: &str) -> IResult<&str, Expr, VerboseError<&str>> {
    let (input, cmd) = not_line_ending(input)?;
    Ok((input, Expr::GenericAugeas(cmd)))
}

/// Read a valid change.
fn expression(input: &str) -> IResult<&str, Expr, VerboseError<&str>> {
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
        "match" => cmd_match(i),
        "values" => cmd_values(i),
        "is" => cmd_is_type(i),
        "save" => Ok((i, Expr::Save)),
        "quit" => Ok((i, Expr::Quit)),
        "load" => Ok((i, Expr::Load)),
        _ => cmd_generic(input),
    }
}

fn line(input: &str) -> IResult<&str, Option<Expr>, VerboseError<&str>> {
    let (input, _) = not(eof)(input)?;
    let (input, _) = space0(input)?;
    let (input, _) = opt(comment)(input)?;
    let (input, e) = opt(expression)(input)?;
    let (input, _) = space0(input)?;
    let (input, _) = opt(comment)(input)?;
    let (input, _) = opt(line_ending)(input)?;
    Ok((input, e))
}

pub fn script(input: &str) -> IResult<&str, Vec<Expr>, VerboseError<&str>> {
    // many0 -> allow empty changes (only comments, etc.)
    let (input, changes) = many0(line)(input)?;
    let exprs = changes.into_iter().flatten().collect();
    let (input, _) = multispace0(input)?;
    let (input, _) = eof(input)?;
    Ok((input, exprs))
}

#[cfg(test)]
mod tests {
    use crate::dsl::parser::{arg_array, expression, line, script};
    use crate::dsl::script::*;
    use crate::dsl::value_type::ValueType;
    use raugeas::Position;

    #[test]
    fn test_match_include() {
        let input = "match /files/etc include token";
        let expected = Expr::MatchInclude("/files/etc".into(), "token");
        let result = expression(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_match_not_include() {
        let input = "match /files/etc not_include token";
        let expected = Expr::MatchNotInclude("/files/etc".into(), "token");
        let result = expression(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_arg_array() {
        let input = "[arg1, 'arg2', \"arg3\"]";
        let expected = vec!["arg1", "arg2", "arg3"];
        let result = arg_array(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_cmd_generic() {
        let input = "generic command";
        let expected = Expr::GenericAugeas("generic command");
        let result = expression(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_change_set() {
        let input = "set /path/to/node value";
        let expected = Expr::Set("/path/to/node".into(), "value");
        let result = expression(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_change_setm() {
        let input = "setm /path/to/nodes subnode value";
        let expected = Expr::SetMultiple("/path/to/nodes".into(), "subnode", "value");
        let result = expression(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_change_rm() {
        let input = "rm /path/to/node";
        let expected = Expr::Remove("/path/to/node".into());
        let result = expression(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_change_clear() {
        let input = "clear /path/to/node";
        let expected = Expr::Clear("/path/to/node".into());
        let result = expression(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_change_clearm() {
        let input = "clearm /path/to/nodes subnode";
        let expected = Expr::ClearMultiple("/path/to/nodes".into(), "subnode");
        let result = expression(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_change_touch() {
        let input = "touch /path/to/node";
        let expected = Expr::Touch("/path/to/node".into());
        let result = expression(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_change_ins() {
        let input = "ins label before /path/to/node";
        let expected = Expr::Insert("label", Position::Before, "/path/to/node".into());
        let result = expression(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_change_mv() {
        let input = "mv /path/to/node /new/path";
        let expected = Expr::Move("/path/to/node".into(), "/new/path".into());
        let result = expression(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_change_rename() {
        let input = "rename /path/to/node new_label";
        let expected = Expr::Rename("/path/to/node".into(), "new_label");
        let result = expression(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_change_defvar() {
        let input = "defvar name /path/to/node";
        let expected = Expr::DefineVar("name", "/path/to/node".into());
        let result = expression(input).unwrap();
        assert_eq!(result.1, expected);
    }

    #[test]
    fn test_change_defnode() {
        let input = "defnode name /path/to/node value";
        let expected = Expr::DefineNode("name", "/path/to/node".into(), "value");
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
        let expected = Expr::Set("/path/to/node".into(), "value");
        let result = line(input).unwrap();
        assert_eq!(result.1, Some(expected));
    }

    #[test]
    fn test_line_command_and_comment() {
        let input = "set /path/to/node value # comment\n";
        let expected = Expr::Set("/path/to/node".into(), "value");
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
    fn test_values_include() {
        let input = "values /path/to/node include value";
        let expected = Expr::ValuesInclude("/path/to/node".into(), "value");
        let result = expression(input).unwrap();
        assert_eq!(result.1, expected);
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
            
            is uint /path/to/node

            print /path/to/node
            quit

            values /path/to/node include value

            ins  label  before        /path/to/node
            mv /path/to/node /new/path
            rename /path/to/node new_label
            defvar name /path/to/node
            defnode name /path/to/node value

        "#;
        let expected = vec![
            Expr::Set("/path/to/node".into(), "value"),
            Expr::SetMultiple("/path/to/nodes".into(), "sub node", "value"),
            Expr::Remove("/path/to/node".into()),
            Expr::Clear("/path/to/node".into()),
            Expr::ClearMultiple("/path/to/nodes".into(), "sub node"),
            Expr::Touch("/path/to/node".into()),
            Expr::HasType("/path/to/node".into(), ValueType::Uint),
            Expr::GenericAugeas("print /path/to/node"),
            Expr::Quit,
            Expr::ValuesInclude("/path/to/node".into(), "value"),
            Expr::Insert("label", Position::Before, "/path/to/node".into()),
            Expr::Move("/path/to/node".into(), "/new/path".into()),
            Expr::Rename("/path/to/node".into(), "new_label"),
            Expr::DefineVar("name", "/path/to/node".into()),
            Expr::DefineNode("name", "/path/to/node".into(), "value"),
        ];
        let result = script(input).unwrap();
        assert_eq!(result.1, expected);
    }
}
