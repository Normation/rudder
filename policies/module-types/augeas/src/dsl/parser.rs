use crate::dsl::{
    comparator::{Comparison, NumComparator},
    script::Script,
    value_type::ValueType,
    AugPath, Sub,
};
use anyhow::{anyhow, Result};
use pest::{iterators::Pair, Parser};
use pest_derive::Parser;
use raugeas::Position;
use zxcvbn::Score;

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
    Set(AugPath<'src>, crate::dsl::Value<'src>),
    /// Sets multiple nodes (matching SUB relative to PATH) to VALUE
    SetMultiple(AugPath<'src>, Sub<'src>, crate::dsl::Value<'src>),
    /// Removes the node at location PATH
    Remove(AugPath<'src>),
    /// Sets the node at PATH to NULL, creating it if needed
    Clear(AugPath<'src>),
    /// Sets multiple nodes (matching SUB relative to PATH) to NULL
    ClearMultiple(AugPath<'src>, Sub<'src>),
    /// Creates PATH with the value NULL if it does not exist
    Touch(AugPath<'src>),
    /// Inserts an empty node LABEL either before or after PATH.
    Insert(crate::dsl::Value<'src>, Position, AugPath<'src>),
    /// Moves a node at PATH to the new location OTHER PATH
    Move(AugPath<'src>, AugPath<'src>),
    /// Copies a node at PATH to the new location OTHER PATH
    Copy(AugPath<'src>, AugPath<'src>),
    /// Rename a node at PATH to a new LABEL
    Rename(AugPath<'src>, crate::dsl::Value<'src>),
    /// Sets Augeas variable $NAME to PATH
    DefineVar(crate::dsl::Value<'src>, AugPath<'src>),
    /// Sets Augeas variable $NAME to PATH, creating it with VALUE if needed
    DefineNode(
        crate::dsl::Value<'src>,
        AugPath<'src>,
        crate::dsl::Value<'src>,
    ),
    // Comparison contains both the typed value and the comparator
    Compare(AugPath<'src>, Comparison),
    ValuesInclude(AugPath<'src>, &'src str),
    ValuesNotInclude(AugPath<'src>, &'src str),
    ValuesEqual(AugPath<'src>, Vec<&'src str>),
    ValuesNotEqual(AugPath<'src>, Vec<&'src str>),
    MatchSize(AugPath<'src>, NumComparator, usize),
    MatchInclude(AugPath<'src>, &'src str),
    MatchNotInclude(AugPath<'src>, &'src str),
    MatchEqual(AugPath<'src>, Vec<&'src str>),
    MatchNotEqual(AugPath<'src>, Vec<&'src str>),
    /// Check the value at the path has a given type
    ///
    /// Uses the "is" keyword.
    HasType(AugPath<'src>, ValueType),
    /// String length
    ///
    /// Warning: do not use for passwords as the value will be displayed.
    StrLen(AugPath<'src>, NumComparator, usize),
    /// Minimal score
    PasswordScore(AugPath<'src>, Score),
    /// Minimal LUDS values
    PasswordLUDS(AugPath<'src>, u8, u8, u8, u8, u8),
    /// Save the changes to the tree.
    Save,
    /// Quit the script.
    Quit,
    /// (Re)load the tree.
    Load,
}

#[derive(Parser)]
#[grammar = "dsl/raugeas.pest"]
pub struct RaugeasParser;

fn parse_array(pair: Pair<Rule>) -> Vec<&str> {
    pair.into_inner().map(|p| p.as_str()).collect()
}

fn parse_command(pair: Pair<Rule>) -> Result<Expr> {
    Ok(match pair.as_rule() {
        Rule::save => Expr::Save,
        Rule::quit => Expr::Quit,
        Rule::set => {
            let mut inner_rules = pair.into_inner();
            let path: &str = inner_rules.next().unwrap().as_str();
            let value: &str = inner_rules.next().unwrap().as_str();
            Expr::Set(path.into(), value)
        }
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
            let new_label: &str = inner_rules.next().unwrap().as_str();
            Expr::Rename(path.into(), new_label)
        }
        Rule::defvar => {
            let mut inner_rules = pair.into_inner();
            let name: &str = inner_rules.next().unwrap().as_str();
            let path: &str = inner_rules.next().unwrap().as_str();
            Expr::DefineVar(name, path.into())
        }
        Rule::defnode => {
            let mut inner_rules = pair.into_inner();
            let name: &str = inner_rules.next().unwrap().as_str();
            let path: &str = inner_rules.next().unwrap().as_str();
            let value: &str = inner_rules.next().unwrap().as_str();
            Expr::DefineNode(name, path.into(), value)
        }
        Rule::values_include => {
            let mut inner_rules = pair.into_inner();
            let path: &str = inner_rules.next().unwrap().as_str();
            let value: &str = inner_rules.next().unwrap().as_str();
            Expr::ValuesInclude(path.into(), value)
        }
        Rule::values_not_include => {
            let mut inner_rules = pair.into_inner();
            let path: &str = inner_rules.next().unwrap().as_str();
            let value: &str = inner_rules.next().unwrap().as_str();
            Expr::ValuesNotInclude(path.into(), value)
        }
        Rule::values_equal => {
            let mut inner_rules = pair.into_inner();
            let path: &str = inner_rules.next().unwrap().as_str();
            let values = parse_array(inner_rules.next().unwrap());
            Expr::ValuesEqual(path.into(), values)
        }
        Rule::values_not_equal => {
            let mut inner_rules = pair.into_inner();
            let path: &str = inner_rules.next().unwrap().as_str();
            let values = parse_array(inner_rules.next().unwrap());
            Expr::ValuesNotEqual(path.into(), values)
        }
        Rule::match_include => {
            let mut inner_rules = pair.into_inner();
            let path: &str = inner_rules.next().unwrap().as_str();
            let value: &str = inner_rules.next().unwrap().as_str();
            Expr::MatchInclude(path.into(), value)
        }
        Rule::match_not_include => {
            let mut inner_rules = pair.into_inner();
            let path: &str = inner_rules.next().unwrap().as_str();
            let value: &str = inner_rules.next().unwrap().as_str();
            Expr::MatchNotInclude(path.into(), value)
        }
        Rule::match_equal => {
            let mut inner_rules = pair.into_inner();
            let path: &str = inner_rules.next().unwrap().as_str();
            let values = parse_array(inner_rules.next().unwrap());
            Expr::MatchEqual(path.into(), values)
        }
        Rule::match_not_equal => {
            let mut inner_rules = pair.into_inner();
            let path: &str = inner_rules.next().unwrap().as_str();
            let values = parse_array(inner_rules.next().unwrap());
            Expr::MatchNotEqual(path.into(), values)
        }
        Rule::strlen => {
            let mut inner_rules = pair.into_inner();
            let path: &str = inner_rules.next().unwrap().as_str();
            let comparator: NumComparator = inner_rules.next().unwrap().as_str().parse()?;
            let size: usize = inner_rules.next().unwrap().as_str().parse()?;
            Expr::StrLen(path.into(), comparator, size)
        }
        Rule::password_score => {
            let mut inner_rules = pair.into_inner();
            let path: &str = inner_rules.next().unwrap().as_str();

            let score_str = inner_rules.next().unwrap().as_str();
            let score: Score = score_str
                .parse::<u8>()?
                .try_into()
                .map_err(|e| anyhow!("Invalid score '{score_str}': {e}"))?;
            Expr::PasswordScore(path.into(), score)
        }
        Rule::password_luds => {
            let mut inner_rules = pair.into_inner();
            let path: &str = inner_rules.next().unwrap().as_str();
            let total: u8 = inner_rules.next().unwrap().as_str().parse()?;
            let lowercase: u8 = inner_rules.next().unwrap().as_str().parse()?;
            let uppercase: u8 = inner_rules.next().unwrap().as_str().parse()?;
            let digit: u8 = inner_rules.next().unwrap().as_str().parse()?;
            let special: u8 = inner_rules.next().unwrap().as_str().parse()?;
            Expr::PasswordLUDS(path.into(), total, lowercase, uppercase, digit, special)
        }
        Rule::load => Expr::Load,
        Rule::insert => {
            let mut inner_rules = pair.into_inner();
            let label: &str = inner_rules.next().unwrap().as_str();
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
            let value: &str = inner_rules.next().unwrap().as_str();
            Expr::SetMultiple(path.into(), sub, value)
        }
        Rule::clear_multiple => {
            let mut inner_rules = pair.into_inner();
            let path: &str = inner_rules.next().unwrap().as_str();
            let sub: &str = inner_rules.next().unwrap().as_str();
            Expr::ClearMultiple(path.into(), sub)
        }
        Rule::has_type => {
            let mut inner_rules = pair.into_inner();
            let path: &str = inner_rules.next().unwrap().as_str();
            let type_: ValueType = inner_rules.next().unwrap().as_str().parse()?;
            Expr::HasType(path.into(), type_)
        }
        Rule::match_size => {
            let mut inner_rules = pair.into_inner();
            let path: &str = inner_rules.next().unwrap().as_str();
            let comparator: NumComparator = inner_rules.next().unwrap().as_str().parse()?;
            let size: usize = inner_rules.next().unwrap().as_str().parse()?;
            Expr::MatchSize(path.into(), comparator, size)
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
    use crate::dsl::comparator::NumComparator::{GreaterThanOrEqual, LessThan};
    use pest::Parser;

    #[test]
    fn pest_parses_strings() {
        assert_eq!(
            RaugeasParser::parse(Rule::string_value, "toto")
                .unwrap()
                .next()
                .unwrap()
                .as_str(),
            "toto"
        );
        assert_eq!(
            RaugeasParser::parse(Rule::string_value, "\"toto\"")
                .unwrap()
                .next()
                .unwrap()
                .as_str(),
            "toto"
        );
        assert_eq!(
            RaugeasParser::parse(Rule::string_value, "'toto'")
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
            RaugeasParser::parse(Rule::array, "[toto]'")
                .unwrap()
                .next()
                .unwrap()
                .as_str(),
            "toto"
        );
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
            
            strlen /path/to/node >= 3

            password_score /path/to/node 3
            password_luds /path/to/node 1 2 3 4 5
            
            has_type /path/to/node ipv4

            match_size /pat/to < 5
        "#;
        let expected = vec![
            Expr::Set("/path/to/node".into(), "value"),
            Expr::Remove("/path/to/node".into()),
            Expr::Clear("/path/to/node".into()),
            Expr::Touch("/path/to/node".into()),
            Expr::Quit,
            Expr::Save,
            Expr::Load,
            Expr::Move("/path/to/node".into(), "/new/path".into()),
            Expr::Move("/path/to/node".into(), "/new/path".into()),
            Expr::Rename("/path/to/node".into(), "new_label"),
            Expr::DefineVar("name", "/path/to/node".into()),
            Expr::DefineNode("name", "/path/to/node".into(), "value"),
            Expr::StrLen(
                AugPath {
                    inner: "/path/to/node",
                },
                GreaterThanOrEqual,
                3,
            ),
            Expr::PasswordScore(
                AugPath {
                    inner: "/path/to/node",
                },
                Score::Three,
            ),
            Expr::PasswordLUDS(
                AugPath {
                    inner: "/path/to/node",
                },
                1,
                2,
                3,
                4,
                5,
            ),
            Expr::HasType(
                AugPath {
                    inner: "/path/to/node",
                },
                ValueType::Ipv4,
            ),
            Expr::MatchSize(AugPath { inner: "/pat/to" }, LessThan, 5),
        ];
        let parsed = parse_script(input).unwrap();
        assert_eq!(parsed.expressions, expected);
    }
}
