// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

//! Rudder expression parser
//!
//! Similar to what the webapp does at generation, but we only use it to lint
//! and/or transform, but not resolve any value.
//!
//! NOTE: All the technique content will ONLY be interpreted by the target platforms, NOT the webapp.
//! We hence only support what the agent support to provide better feedback to the developers.
//! i.e. no `| options`, so `${ spaces . anywhere }`, etc.

// TODO: add warnings when using instance-specific values (node properties, etc.)
// TODO: specific parser for condition expressions

use std::{cmp::Ordering, str::FromStr};

use anyhow::{bail, Error, Result};
use nom::{
    branch::alt,
    bytes::complete::{tag, take_till},
    character::complete::char,
    combinator::{map, verify},
    multi::{many0, many1},
    sequence::{preceded, terminated},
    Finish, IResult,
};
use rudder_commons::Target;

// from clap https://github.com/clap-rs/clap/blob/1f71fd9e992c2d39a187c6bd1f015bdfe77dbadf/clap_builder/src/parser/features/suggestions.rs#L11
// under MIT/Apache 2.0 licenses.
/// Find strings from an iterable of `possible_values` similar to a given value `v`
/// Returns a Vec of all possible values that exceed a similarity threshold
/// sorted by ascending similarity, most similar comes last
pub fn did_you_mean<T, I>(v: &str, possible_values: I) -> Vec<String>
where
    T: AsRef<str>,
    I: IntoIterator<Item = T>,
{
    let mut candidates: Vec<(f64, String)> = possible_values
        .into_iter()
        // GH #4660: using `jaro` because `jaro_winkler` implementation in `strsim-rs` is wrong
        // causing strings with common prefix >=10 to be considered perfectly similar
        .map(|pv| (strsim::jaro(v, pv.as_ref()), pv.as_ref().to_owned()))
        // Confidence of 0.7 so that bar -> baz is suggested
        .filter(|(confidence, _)| *confidence > 0.7)
        .collect();
    candidates.sort_by(|a, b| a.0.partial_cmp(&b.0).unwrap_or(Ordering::Equal));
    candidates.into_iter().map(|(_, pv)| pv).collect()
}

/// Rudder variable expression.
///
/// Note: This is not exactly the expressions accepted by the agents and there is a small translation layer.
#[derive(Debug, PartialEq, Clone)]
// ${node.properties[dns_${sys.host}]}
pub enum Expression {
    /// `${sys.host}`, `${sys.hardware_mac[eth0]}`
    ///
    /// The first element of the vec is the name of the variable, it must not be empty
    Sys(Vec<Expression>),
    /// `${const.dollar}`
    Const(Box<Expression>),
    /// `${ncf_const.s}`
    NcfConst(Box<Expression>),
    /// `${node.inventory[os]}`
    NodeInventory(Vec<Expression>),
    // FIXME deprecated in techniques (in favor of technique parameters)
    /// `${rudder.parameters[NAME]}`
    GlobalParameter(Box<Expression>),
    // FIXME deprecated in techniques (in favor of technique parameters)
    /// `${node.property[KEY][SUBKEY]}`
    NodeProperty(Vec<Expression>),
    /// `${anything_unidentified}` (all other variable expressions)
    OtherVar(Box<Expression>),
    /// A static value
    Scalar(String),
    /// A list of tokens
    Sequence(Vec<Expression>),
}

impl FromStr for Expression {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        match expression(s).finish() {
            Ok((_, e)) => Ok(e),
            Err(e) => bail!("Invalid expression '{}' with {:?}", s, e),
        }
    }
}

impl Expression {
    /// Look for errors in the expression
    pub fn lint(&self) -> Result<()> {
        match self {
            Self::Sequence(s) => {
                for e in s {
                    e.lint()?;
                }
            }
            // TODO: Maybe check for technique parameters, without dots
            Self::OtherVar(e) => e.lint()?,
            Self::Scalar(_) => (),
            Self::NodeProperty(s) => {
                for e in s {
                    e.lint()?;
                }
            }
            Self::NodeInventory(s) => {
                for e in s {
                    e.lint()?;
                }
            }
            Self::GlobalParameter(p) => p.lint()?,
            Self::Sys(s) => {
                for e in s {
                    e.lint()?;
                }
            }
            Self::Const(e) => e.lint()?,
            Self::NcfConst(e) => e.lint()?,
        }
        Ok(())
    }

    pub fn fmt(&self, target: Target) -> String {
        match self {
            Self::Sequence(s) => s
                .iter()
                .map(|i| i.fmt(target))
                .collect::<Vec<String>>()
                .join(""),
            Self::OtherVar(e) => format!("${{{}}}", e.fmt(target)),
            Self::Scalar(s) => s.to_string(),
            Self::NodeProperty(e) => {
                let keys = e
                    .iter()
                    .map(|i| i.fmt(target))
                    .collect::<Vec<String>>()
                    .join("][");
                match target {
                    Target::Unix => format!("${{node.properties[{}]}}", keys),
                    Target::Windows => format!("$($node.properties[{}])", keys),
                }
            }
            Self::NodeInventory(e) => {
                let keys = e
                    .iter()
                    .map(|i| i.fmt(target))
                    .collect::<Vec<String>>()
                    .join("][");
                match target {
                    Target::Unix => format!("${{node.inventory[{}]}}", keys),
                    Target::Windows => format!("$($node.inventory[{}])", keys),
                }
            }
            Self::GlobalParameter(p) => format!("${{rudder.parameters[{}]}}", p.fmt(target)),
            Self::Sys(e) => {
                if e.len() == 1 {
                    format!("${{sys.{}}}", e[0].fmt(target))
                } else {
                    let keys = e
                        .iter()
                        .skip(1)
                        .map(|i| i.fmt(target))
                        .collect::<Vec<String>>()
                        .join("][");
                    match target {
                        Target::Unix => format!("${{sys.{}[{}]}}", e[0].fmt(target), keys),
                        Target::Windows => format!("$($sys.{}[{}])", e[0].fmt(target), keys),
                    }
                }
            }
            Self::Const(e) => format!("${{const.{}}}", e.fmt(target)),
            Self::NcfConst(e) => format!("${{ncf_const.{}}}", e.fmt(target)),
        }
    }
}

/// Parses valid expressions
fn expression(s: &str) -> IResult<&str, Expression> {
    map(
        // NOTE: parser used in many0 must not accept empty input
        many0(alt((
            // different types of known variables
            node_properties,
            node_inventory,
            parameter,
            sys,
            const_,
            ncf_const,
            // default var
            other_var,
            // default
            string,
        ))),
        Expression::Sequence,
    )(s)
}

// Reads non-empty string until beginning or end of variable
fn string(s: &str) -> IResult<&str, Expression> {
    map(
        verify(
            take_till(|c| ['[', ']', '}', '$'].contains(&c)),
            |s: &str| !s.is_empty(),
        ),
        |out: &str| Expression::Scalar(out.to_string()),
    )(s)
}

// Reads a node property
fn other_var(s: &str) -> IResult<&str, Expression> {
    preceded(
        tag("${"),
        terminated(
            map(expression, |out| Expression::OtherVar(Box::new(out))),
            char('}'),
        ),
    )(s)
}

fn parameter(s: &str) -> IResult<&str, Expression> {
    preceded(
        tag("${rudder.parameters"),
        terminated(
            map(key, |out| Expression::GlobalParameter(Box::new(out))),
            char('}'),
        ),
    )(s)
}

fn sys(s: &str) -> IResult<&str, Expression> {
    let (s, _) = tag("${sys.")(s)?;
    // Property name, mandatory
    let (s, name) = expression(s)?;
    // Keys, optional
    let (s, mut keys) = many0(key)(s)?;
    let (s, _) = char('}')(s)?;
    let mut res = vec![name];
    res.append(&mut keys);
    Ok((s, Expression::Sys(res)))
}

fn const_(s: &str) -> IResult<&str, Expression> {
    preceded(
        tag("${const."),
        terminated(map(key, |out| Expression::Const(Box::new(out))), char('}')),
    )(s)
}

fn ncf_const(s: &str) -> IResult<&str, Expression> {
    preceded(
        tag("${ncf_const."),
        terminated(map(key, |out| Expression::Const(Box::new(out))), char('}')),
    )(s)
}

// Reads a node property
fn node_properties(s: &str) -> IResult<&str, Expression> {
    preceded(
        tag("${node.properties"),
        terminated(map(many1(key), Expression::NodeProperty), char('}')),
    )(s)
}

// Reads a node inventory value
fn node_inventory(s: &str) -> IResult<&str, Expression> {
    preceded(
        tag("${node.inventory"),
        terminated(map(many1(key), Expression::NodeProperty), char('}')),
    )(s)
}

// Reads a key in square brackets
fn key(s: &str) -> IResult<&str, Expression> {
    preceded(char('['), terminated(expression, char(']')))(s)
}

#[cfg(test)]
mod tests {
    use pretty_assertions::assert_eq;

    use super::*;

    #[test]
    fn it_reads_string() {
        let (_, out) = string("toto").unwrap();
        assert_eq!(out, Expression::Scalar("toto".to_string()));
        let (_, out) = string("toto]").unwrap();
        assert_eq!(out, Expression::Scalar("toto".to_string()));
        let (_, out) = string("toto]to").unwrap();
        assert_eq!(out, Expression::Scalar("toto".to_string()));
        let (_, out) = string("toto${toto}").unwrap();
        assert_eq!(out, Expression::Scalar("toto".to_string()));
        let (_, out) = string("toto}plop").unwrap();
        assert_eq!(out, Expression::Scalar("toto".to_string()));
    }

    #[test]
    fn it_reads_expression() {
        let (_, out) = expression("toto").unwrap();
        assert_eq!(
            out,
            Expression::Sequence(vec![Expression::Scalar("toto".to_string())])
        );
        let (_, out) = expression("toto]").unwrap();
        assert_eq!(
            out,
            Expression::Sequence(vec![Expression::Scalar("toto".to_string())])
        );
        let (_, out) = expression("toto}").unwrap();
        assert_eq!(
            out,
            Expression::Sequence(vec![Expression::Scalar("toto".to_string())])
        );
        let (_, out) = expression("${sys.host}").unwrap();
        assert_eq!(
            out,
            Expression::Sequence(vec![Expression::Sys(vec![Expression::Sequence(vec![
                Expression::Scalar("host".to_string())
            ])])])
        );
        let (_, out) = expression("${sys.interface_flags[eth0]}").unwrap();
        assert_eq!(
            out,
            Expression::Sequence(vec![Expression::Sys(vec![
                Expression::Sequence(vec![Expression::Scalar("interface_flags".to_string())]),
                Expression::Sequence(vec![Expression::Scalar("eth0".to_string())])
            ])])
        );
    }

    #[test]
    fn it_reads_keys() {
        let (_, out) = key("[toto]").unwrap();
        assert_eq!(
            out,
            Expression::Sequence(vec![Expression::Scalar("toto".to_string())])
        )
    }

    #[test]
    fn it_reads_node_properties() {
        let (_, out) = node_properties("${node.properties[toto]}").unwrap();
        assert_eq!(
            out,
            Expression::NodeProperty(vec![Expression::Sequence(vec![Expression::Scalar(
                "toto".to_string()
            )])])
        );
        let (_, out) = node_properties("${node.properties[toto][tutu]}").unwrap();
        assert_eq!(
            out,
            Expression::NodeProperty(vec![
                Expression::Sequence(vec![Expression::Scalar("toto".to_string())]),
                Expression::Sequence(vec![Expression::Scalar("tutu".to_string())])
            ])
        );
        let (_, out) =
            node_properties("${node.properties[${node.properties[inner]}][tutu]}").unwrap();
        assert_eq!(
            out,
            Expression::NodeProperty(vec![
                Expression::Sequence(vec![Expression::NodeProperty(vec![Expression::Sequence(
                    vec![Expression::Scalar("inner".to_string())]
                ),])]),
                Expression::Sequence(vec![Expression::Scalar("tutu".to_string())]),
            ])
        );
    }

    #[test]
    fn it_reads_generic_var() {
        let (_, out) = other_var("${plouf}").unwrap();
        assert_eq!(
            out,
            Expression::OtherVar(Box::new(Expression::Sequence(vec![Expression::Scalar(
                "plouf".to_string()
            )])))
        );
    }

    #[test]
    fn it_reads_parameters() {
        let (_, out) = parameter("${rudder.parameters[plouf]}").unwrap();
        assert_eq!(
            out,
            Expression::GlobalParameter(Box::new(Expression::Sequence(vec![Expression::Scalar(
                "plouf".to_string()
            )])))
        );
    }

    #[test]
    fn it_formats_expressions() {
        let e = Expression::NodeProperty(vec![
            Expression::Sequence(vec![Expression::NodeProperty(vec![Expression::Sequence(
                vec![
                    Expression::Scalar("inner".to_string()),
                    Expression::Sys(vec![Expression::Sequence(vec![Expression::Scalar(
                        "host".to_string(),
                    )])]),
                    Expression::Sys(vec![
                        Expression::Sequence(vec![Expression::Scalar("interfaces".to_string())]),
                        Expression::Sequence(vec![Expression::Scalar("eth0".to_string())]),
                    ]),
                ],
            )])]),
            Expression::Sequence(vec![Expression::Scalar("tutu".to_string())]),
        ]);
        assert_eq!(
            e.fmt(Target::Unix),
            "${node.properties[${node.properties[inner${sys.host}${sys.interfaces[eth0]}]}][tutu]}"
                .to_string()
        );
        assert_eq!(
            e.fmt(Target::Windows),
            "$($node.properties[$($node.properties[inner${sys.host}$($sys.interfaces[eth0])])][tutu])".to_string()
        );
    }

    #[test]
    fn it_suggests_values() {
        let values = ["Alexis", "Félix", "Vincent"];
        assert_eq!(did_you_mean("Félou", values).last().unwrap(), "Félix");
        assert_eq!(did_you_mean("Vince", values).last().unwrap(), "Vincent");
    }
}
