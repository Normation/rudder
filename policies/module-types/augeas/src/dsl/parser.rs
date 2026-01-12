// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS

use crate::dsl::{
    AugPath, Sub,
    comparator::{
        Comparison, NumComparator, Number, NumericComparison, StrComparator, StrValidation,
    },
    script::Script,
    value_type::ValueType,
};
use anyhow::{Result, anyhow};
use pest::{
    Parser,
    iterators::{Pair, Pairs},
};
use pest_derive::Parser;
use raugeas::Position;
use zxcvbn::Score;

/// A check expression, unique to the Rudder extended Augeas language.
///
/// All check expressions apply to a match expression.
#[derive(Clone, Debug, PartialEq)]
pub enum CheckExpr {
    /// Check the value at the path has a given type
    ///
    /// Uses the "is" keyword.
    HasType(ValueType),
    /// String length
    ///
    /// Warning: do not use for passwords as the value will be displayed.
    Len(NumComparator, usize),
    /// Minimal score
    PasswordScore(Score),
    /// Minimal LUDS values
    PasswordLUDS(u8, u8, u8, u8, u8),
    /// Check the size of the array at the path
    ValuesLen(NumComparator, usize),
    // Comparison contains both the typed value and the comparator
    Compare(Comparison),
    ValuesInclude(String),
    ValuesNotInclude(String),
    ValuesEqual(Vec<String>),
    ValuesEqualOrdered(Vec<String>),
    ValuesIn(Vec<String>),
    InIpRange(Vec<String>),
}

/// A command of the extended Augeas language used in Rudder.
///
/// Note: We implement all the command modify either the tree or the system,
/// so we can control changes in the interpreter.
/// Most read commands are passed unchanged to the Augeas interpreter.
#[derive(Clone, Debug, PartialEq)]
pub enum Expr<'src> {
    /// A generic augeas command, not parsed.
    GenericAugeas(&'src str),
    /// Sets the value VALUE at location PATH
    Set(AugPath<'src>, String),
    Get(AugPath<'src>),
    /// Sets multiple nodes (matching SUB relative to PATH) to VALUE
    SetMultiple(AugPath<'src>, Sub<'src>, String),
    /// Removes the node at location PATH
    Remove(AugPath<'src>),
    /// Sets the node at PATH to NULL, creating it if needed
    Clear(AugPath<'src>),
    /// Sets multiple nodes (matching SUB relative to PATH) to NULL
    ClearMultiple(AugPath<'src>, Sub<'src>),
    /// Creates PATH with the value NULL if it does not exist
    Touch(AugPath<'src>),
    /// Inserts an empty node LABEL either before or after PATH.
    Insert(String, Position, AugPath<'src>),
    /// Moves a node at PATH to the new location OTHER PATH
    Move(AugPath<'src>, AugPath<'src>),
    /// Copies a node at PATH to the new location OTHER PATH
    Copy(AugPath<'src>, AugPath<'src>),
    /// Rename a node at PATH to a new LABEL
    Rename(AugPath<'src>, String),
    /// Sets Augeas variable $NAME to PATH
    DefineVar(String, AugPath<'src>),
    /// Sets Augeas variable $NAME to PATH, creating it with VALUE if needed
    DefineNode(String, AugPath<'src>, String),
    Check(AugPath<'src>, CheckExpr),
    /// Save the changes to the tree.
    Save,
    /// Quit the script.
    Quit,
    /// (Re)load the tree.
    Load,
    /// Load a file in the Augeas tree.
    LoadFile(AugPath<'src>),
}

#[derive(Parser)]
#[grammar = "dsl/raugeas.pest"]
pub struct RaugeasParser;

fn parse_array(pair: Pairs<Rule>) -> Vec<String> {
    pair.map(|p| unescape_string(p.as_str())).collect()
}

fn unescape_string(s: &str) -> String {
    let mut result = String::new();
    let mut chars = s.chars();
    while let Some(c) = chars.next() {
        if c == '\\' {
            match chars.next() {
                Some('"') => result.push('"'),
                Some('\'') => result.push('\''),
                Some('\\') => result.push('\\'),
                Some('n') => result.push('\n'),
                Some('r') => result.push('\r'),
                Some('t') => result.push('\t'),
                Some(c) => {
                    result.push('\\');
                    result.push(c);
                }
                None => result.push('\\'),
            }
        } else {
            result.push(c);
        }
    }
    result
}

fn parse_check_command(pair: Pair<Rule>) -> Result<CheckExpr> {
    Ok(match pair.as_rule() {
        Rule::values_include => {
            let mut inner_rules = pair.into_inner();
            let value: String = unescape_string(inner_rules.next().unwrap().as_str());
            CheckExpr::ValuesInclude(value)
        }
        Rule::values_not_include => {
            let mut inner_rules = pair.into_inner();
            let value: String = unescape_string(inner_rules.next().unwrap().as_str());
            CheckExpr::ValuesNotInclude(value)
        }
        Rule::values_equal => CheckExpr::ValuesEqual(parse_array(pair.into_inner())),
        Rule::values_equal_ordered => CheckExpr::ValuesEqualOrdered(parse_array(pair.into_inner())),
        Rule::values_in => CheckExpr::ValuesIn(parse_array(pair.into_inner())),
        Rule::len => {
            let mut inner_rules = pair.into_inner();
            let comparator: NumComparator = inner_rules.next().unwrap().as_str().parse()?;
            let size: usize = inner_rules.next().unwrap().as_str().parse()?;
            CheckExpr::Len(comparator, size)
        }
        Rule::password_score => {
            let mut inner_rules = pair.into_inner();

            let score_str = inner_rules.next().unwrap().as_str();
            let score: Score = score_str
                .parse::<u8>()?
                .try_into()
                .map_err(|e| anyhow!("Invalid score '{score_str}': {e}"))?;
            CheckExpr::PasswordScore(score)
        }
        Rule::password_tluds => {
            let mut inner_rules = pair.into_inner();
            let total: u8 = inner_rules.next().unwrap().as_str().parse()?;
            let lowercase: u8 = inner_rules.next().unwrap().as_str().parse()?;
            let uppercase: u8 = inner_rules.next().unwrap().as_str().parse()?;
            let digit: u8 = inner_rules.next().unwrap().as_str().parse()?;
            let special: u8 = inner_rules.next().unwrap().as_str().parse()?;
            CheckExpr::PasswordLUDS(total, lowercase, uppercase, digit, special)
        }
        Rule::has_type => {
            let mut inner_rules = pair.into_inner();
            let type_: ValueType = inner_rules.next().unwrap().as_str().parse()?;
            CheckExpr::HasType(type_)
        }
        Rule::values_len => {
            let mut inner_rules = pair.into_inner();
            let comparator: NumComparator = inner_rules.next().unwrap().as_str().parse()?;
            let size: usize = inner_rules.next().unwrap().as_str().parse()?;
            CheckExpr::ValuesLen(comparator, size)
        }
        Rule::compare_num => {
            let mut inner_rules = pair.into_inner();
            let comparator: NumComparator = inner_rules.next().unwrap().as_str().parse()?;
            let value: Number = inner_rules.next().unwrap().as_str().parse()?;
            let comparison = NumericComparison { comparator, value };
            CheckExpr::Compare(Comparison::Num(comparison))
        }
        Rule::compare_string => {
            let mut inner_rules = pair.into_inner();
            let comparator: StrComparator = inner_rules.next().unwrap().as_str().parse()?;
            let value = inner_rules.next().unwrap().as_str().to_string();
            let comparison = StrValidation { comparator, value };
            CheckExpr::Compare(Comparison::Str(comparison))
        }
        Rule::in_ip_range => CheckExpr::InIpRange(parse_array(pair.into_inner())),
        _ => unreachable!("Unexpected check rule: {:?}", pair.as_rule()),
    })
}

fn parse_command(pair: Pair<Rule>) -> Result<Expr> {
    Ok(match pair.as_rule() {
        Rule::check => {
            let mut inner_rules = pair.into_inner();
            let path: &str = inner_rules.next().unwrap().as_str();
            Expr::Check(
                path.into(),
                parse_check_command(inner_rules.next().unwrap())?,
            )
        }
        Rule::save => Expr::Save,
        Rule::quit => Expr::Quit,
        Rule::set => {
            let mut inner_rules = pair.into_inner();
            let path: &str = inner_rules.next().unwrap().as_str();
            let value: String = unescape_string(inner_rules.next().unwrap().as_str());
            Expr::Set(path.into(), value)
        }
        Rule::get => Expr::Get(pair.into_inner().next().unwrap().as_str().into()),
        Rule::rm => Expr::Remove(pair.into_inner().next().unwrap().as_str().into()),
        Rule::clear => Expr::Clear(pair.into_inner().next().unwrap().as_str().into()),
        Rule::touch => Expr::Touch(pair.into_inner().next().unwrap().as_str().into()),
        Rule::mv => {
            let mut inner_rules = pair.into_inner();
            let path: &str = inner_rules.next().unwrap().as_str();
            let new_path: &str = inner_rules.next().unwrap().as_str();
            Expr::Move(path.into(), new_path.into())
        }
        Rule::rename => {
            let mut inner_rules = pair.into_inner();
            let path: &str = inner_rules.next().unwrap().as_str();
            let new_label: String = unescape_string(inner_rules.next().unwrap().as_str());
            Expr::Rename(path.into(), new_label)
        }
        Rule::defvar => {
            let mut inner_rules = pair.into_inner();
            let name: String = unescape_string(inner_rules.next().unwrap().as_str());
            let path: &str = inner_rules.next().unwrap().as_str();
            Expr::DefineVar(name, path.into())
        }
        Rule::defnode => {
            let mut inner_rules = pair.into_inner();
            let name: String = unescape_string(inner_rules.next().unwrap().as_str());
            let path: &str = inner_rules.next().unwrap().as_str();
            let value: String = unescape_string(inner_rules.next().unwrap().as_str());
            Expr::DefineNode(name, path.into(), value)
        }
        Rule::load => Expr::Load,
        Rule::load_file => Expr::LoadFile(pair.into_inner().next().unwrap().as_str().into()),
        Rule::insert => {
            let mut inner_rules = pair.into_inner();
            let label: String = unescape_string(inner_rules.next().unwrap().as_str());
            let position = match inner_rules.next().unwrap().as_str() {
                "before" => Position::Before,
                "after" => Position::After,
                _ => unreachable!(),
            };
            let path: &str = inner_rules.next().unwrap().as_str();
            Expr::Insert(label, position, path.into())
        }
        Rule::cp => {
            let mut inner_rules = pair.into_inner();
            let path: &str = inner_rules.next().unwrap().as_str();
            let new_path: &str = inner_rules.next().unwrap().as_str();
            Expr::Copy(path.into(), new_path.into())
        }
        Rule::set_multiple => {
            let mut inner_rules = pair.into_inner();
            let path: &str = inner_rules.next().unwrap().as_str();
            let sub: &str = inner_rules.next().unwrap().as_str();
            let value: String = unescape_string(inner_rules.next().unwrap().as_str());
            Expr::SetMultiple(path.into(), sub, value)
        }
        Rule::clear_multiple => {
            let mut inner_rules = pair.into_inner();
            let path: &str = inner_rules.next().unwrap().as_str();
            let sub: &str = inner_rules.next().unwrap().as_str();
            Expr::ClearMultiple(path.into(), sub)
        }
        Rule::other_command => {
            let command = pair.as_str();
            Expr::GenericAugeas(command)
        }
        _ => unreachable!("Unexpected rule: {:?}", pair.as_rule()),
    })
}

pub fn parse_script(input: &str) -> Result<Script<'_>> {
    let parsed = RaugeasParser::parse(Rule::script, input)?.next().unwrap();

    let mut expressions = Vec::new();

    for line in parsed.into_inner() {
        match line.as_rule() {
            Rule::command => expressions.push(parse_command(line.into_inner().next().unwrap())?),
            Rule::COMMENT | Rule::EOI => {}
            _ => {
                dbg!(&line);
            }
        }
    }

    Ok(Script { expressions })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::dsl::{
        comparator::NumComparator::{GreaterThanOrEqual, LessThan},
        parser::CheckExpr::{ValuesEqual, ValuesEqualOrdered},
    };
    use pest::Parser;

    #[test]
    fn pest_parses_strings() {
        assert_eq!(
            RaugeasParser::parse(Rule::string, "toto")
                .unwrap()
                .next()
                .unwrap()
                .as_str(),
            "toto"
        );
        assert_eq!(
            RaugeasParser::parse(Rule::string, "\"toto\"")
                .unwrap()
                .next()
                .unwrap()
                .as_str(),
            "toto"
        );
        assert_eq!(
            RaugeasParser::parse(Rule::string, "'toto'")
                .unwrap()
                .next()
                .unwrap()
                .as_str(),
            "toto"
        );
    }

    #[test]
    fn pest_parses_arrays() {
        assert_eq!(
            parse_array(RaugeasParser::parse(Rule::array, "[toto, 'titi', \"tutu\"]'").unwrap()),
            vec!["toto", "titi", "tutu"]
        );
    }

    #[test]
    fn parser_correctly_handles_escaped_strings() {
        let input = r#"
            set /path/to/node "value with spaces \t and a \" quote"
            set /path/to/other_node 'another \\ value with spaces and a \' quote'
            check /path/to/node values == ["value1\"thing", "value2\"thing"]
        "#;
        let expected = vec![
            Expr::Set(
                "/path/to/node".into(),
                "value with spaces \t and a \" quote".to_string(),
            ),
            Expr::Set(
                "/path/to/other_node".into(),
                "another \\ value with spaces and a ' quote".to_string(),
            ),
            Expr::Check(
                AugPath {
                    inner: "/path/to/node",
                },
                ValuesEqual(vec![
                    "value1\"thing".to_string(),
                    "value2\"thing".to_string(),
                ]),
            ),
        ];
        let parsed = parse_script(input).unwrap();
        assert_eq!(parsed.expressions, expected);
    }

    #[test]
    fn pest_parse_script() {
        let input = r#"
            # This is a comment
            set /path/to/node value
            rm /path/to/node
            # other comment

            clear /path/to/node # another command
            touch /path/to/node

            quit
            save
            load

            mv   /path/to/node /new/path
            move /path/to/node /new/path
            rename /path/to/node new_label
            defvar name /path/to/node
            defnode name /path/to/node value
            
            check /path/to/node len >= 3

            check /path/to/node password score 3
            check /path/to/node password tluds 1 2 3 4 5
            
            check /path/to/node is ipv4

            check /pat/to values len < 5
            
            check /path/to/node values == ["value1", "value2"]
            check /path/to/node values === ["value1", "value2"]
        "#;
        let expected = vec![
            Expr::Set("/path/to/node".into(), "value".to_string()),
            Expr::Remove("/path/to/node".into()),
            Expr::Clear("/path/to/node".into()),
            Expr::Touch("/path/to/node".into()),
            Expr::Quit,
            Expr::Save,
            Expr::Load,
            Expr::Move("/path/to/node".into(), "/new/path".into()),
            Expr::Move("/path/to/node".into(), "/new/path".into()),
            Expr::Rename("/path/to/node".into(), "new_label".to_string()),
            Expr::DefineVar("name".to_string(), "/path/to/node".into()),
            Expr::DefineNode(
                "name".to_string(),
                "/path/to/node".into(),
                "value".to_string(),
            ),
            Expr::Check(
                AugPath {
                    inner: "/path/to/node",
                },
                CheckExpr::Len(GreaterThanOrEqual, 3),
            ),
            Expr::Check(
                AugPath {
                    inner: "/path/to/node",
                },
                CheckExpr::PasswordScore(Score::Three),
            ),
            Expr::Check(
                AugPath {
                    inner: "/path/to/node",
                },
                CheckExpr::PasswordLUDS(1, 2, 3, 4, 5),
            ),
            Expr::Check(
                AugPath {
                    inner: "/path/to/node",
                },
                CheckExpr::HasType(ValueType::Ipv4),
            ),
            Expr::Check(
                AugPath { inner: "/pat/to" },
                CheckExpr::ValuesLen(LessThan, 5),
            ),
            Expr::Check(
                AugPath {
                    inner: "/path/to/node",
                },
                ValuesEqual(vec!["value1".to_string(), "value2".to_string()]),
            ),
            Expr::Check(
                AugPath {
                    inner: "/path/to/node",
                },
                ValuesEqualOrdered(vec!["value1".to_string(), "value2".to_string()]),
            ),
        ];
        let parsed = parse_script(input).unwrap();
        assert_eq!(parsed.expressions, expected);
    }
}
