// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

//! Rudder expression parser
//!
//! Similar to what the webapp does at generation, but we only use it to lint
//! and/or transform, but not resolve any value.
//!
//! NOTE: All the technique content will ONLY be interpreted by the target platforms, NOT the webapp.
//! We hence only support what the agent support to provide better feedback to the developers.
//! I.e., no `| options`, so `${ spaces . anywhere }`, etc.

// TODO: add warnings when using instance-specific values (node properties, etc.)
// TODO: specific parser for condition expressions

use std::{cmp::Ordering, fmt::Debug, str::FromStr, sync::OnceLock};

use anyhow::{Error, Result, bail};
use nom::{
    Finish, IResult, Parser,
    branch::alt,
    bytes::complete::{tag, take_till, take_until, take_while},
    character::complete::char,
    combinator::{eof, map, verify},
    multi::{many0, many1},
    sequence::{preceded, terminated},
};
use rudder_commons::Target;
use tracing::{debug, warn};

use super::technique;

// from clap https://github.com/clap-rs/clap/blob/1f71fd9e992c2d39a187c6bd1f015bdfe77dbadf/clap_builder/src/parser/features/suggestions.rs#L11
// under MIT/Apache 2.0 licenses.
/// Find strings from an iterable of `possible_values` similar to a given value `v`
/// Returns Some(v) if a similar value was found
pub fn did_you_mean<T, I>(v: &str, possible_values: I) -> Option<String>
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
    let res: Vec<String> = candidates.into_iter().map(|(_, pv)| pv).collect();
    res.last().cloned()
}

/// Known vars, for now no distinction between OSes
///
/// Allows checking for incorrect expressions.
pub fn known_vars() -> &'static serde_yaml::Value {
    static KNOWN_VAR: OnceLock<serde_yaml::Value> = OnceLock::new();
    KNOWN_VAR.get_or_init(|| {
        let str = include_str!("../../libs/vars.yml");
        serde_yaml::from_str(str).unwrap()
    })
}

fn nustache_render(s: &str) -> String {
    format!("[Rudder.Datastate]::Render('{{{{{{' + {s} + '}}}}}}')")
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
    /// `${anything.unknown[but][valid]}`
    GenericVar(Vec<Expression>),
    /// A static value
    Scalar(String),
    /// A list of tokens
    Sequence(Vec<Expression>),
    /// An empty expression
    Empty,
}

impl FromStr for Expression {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        match complete_expression(s).finish() {
            Ok((_, e)) => {
                debug!("Expression identified from '{}' ----> {:?}", s, e);
                Ok(e)
            }
            Err(e) => bail!("Invalid expression '{}' with {:?}", s, e),
        }
    }
}

impl Expression {
    pub fn force_long_name_for_technique_params(
        self,
        target: Target,
        t_id: &str,
        t_parameters: Vec<technique::Parameter>,
    ) -> Result<Self> {
        match self.clone() {
            Expression::Empty => Ok(self),
            Expression::Scalar(_) => Ok(self),
            Expression::Const(e) => Ok(Expression::Const(Box::new(
                e.force_long_name_for_technique_params(target, t_id, t_parameters)?,
            ))),
            Expression::GlobalParameter(e) => Ok(Expression::GlobalParameter(Box::new(
                e.force_long_name_for_technique_params(target, t_id, t_parameters)?,
            ))),
            Expression::NcfConst(e) => Ok(Expression::NcfConst(Box::new(
                e.force_long_name_for_technique_params(target, t_id, t_parameters)?,
            ))),
            Expression::Sys(v) => Ok(Expression::Sys(
                v.iter()
                    .map(|e| {
                        e.clone().force_long_name_for_technique_params(
                            target,
                            t_id,
                            t_parameters.clone(),
                        )
                    })
                    .collect::<Result<Vec<Expression>>>()?,
            )),
            Expression::NodeInventory(v) => Ok(Expression::NodeInventory(
                v.iter()
                    .map(|e| {
                        e.clone().force_long_name_for_technique_params(
                            target,
                            t_id,
                            t_parameters.clone(),
                        )
                    })
                    .collect::<Result<Vec<Expression>>>()?,
            )),
            Expression::NodeProperty(v) => Ok(Expression::NodeProperty(
                v.iter()
                    .map(|e| {
                        e.clone().force_long_name_for_technique_params(
                            target,
                            t_id,
                            t_parameters.clone(),
                        )
                    })
                    .collect::<Result<Vec<Expression>>>()?,
            )),
            Expression::Sequence(v) => Ok(Expression::Sequence(
                v.iter()
                    .map(|e| {
                        e.clone().force_long_name_for_technique_params(
                            target,
                            t_id,
                            t_parameters.clone(),
                        )
                    })
                    .collect::<Result<Vec<Expression>>>()?,
            )),
            Expression::GenericVar(v) => {
                if v.len() == 1 {
                    // If the generic var is made of one unique scalar and does not contain any '.'
                    // it is most likely a parameter call using the short name
                    match v[0].clone() {
                        Expression::Scalar(_) => {
                            if t_parameters.iter().any(|s| s.name == v[0].fmt(target)) {
                                Ok(Expression::GenericVar(vec![
                                    Expression::Scalar(t_id.to_string()),
                                    v[0].clone(),
                                ]))
                            } else {
                                Ok(self)
                            }
                        }
                        _ => v[0].clone().force_long_name_for_technique_params(
                            target,
                            t_id,
                            t_parameters,
                        ),
                    }
                } else {
                    Ok(Expression::GenericVar(
                        v.iter()
                            .map(|e| {
                                e.clone().force_long_name_for_technique_params(
                                    target,
                                    t_id,
                                    t_parameters.clone(),
                                )
                            })
                            .collect::<Result<Vec<Expression>>>()?,
                    ))
                }
            }
        }
    }
    /// Look for errors in the expression
    //
    // A lot of unwrapping as we rely on the structure of the `vars.yml` static document
    pub fn lint(&self) -> Result<()> {
        let known_vars = known_vars();
        match self {
            Self::Sequence(s) => {
                for e in s {
                    e.lint()?;
                }
            }
            // TODO: Maybe check for technique parameters, without dots
            Self::GenericVar(v) => {
                for e in v {
                    e.lint()?;
                }
            }
            Self::Scalar(_) => (),
            Self::NodeProperty(s) => {
                for e in s {
                    e.lint()?;
                }
            }
            Self::NodeInventory(s) => {
                let inventory = known_vars.get("inventory").unwrap().as_sequence().unwrap();
                // First level
                let vals: Vec<&str> = inventory
                    .iter()
                    .map(|v| {
                        if let Some(m) = v.as_mapping() {
                            m.keys().next().unwrap().as_str().unwrap()
                        } else {
                            v.as_str().unwrap()
                        }
                    })
                    .collect();
                if let Expression::Scalar(k1) = &s[0] {
                    if !vals.contains(&k1.as_str()) {
                        if let Some(prop) = did_you_mean(k1, vals) {
                            warn!(
                                "Unknown variable 'node.inventory[{k1}]', did you mean '{prop}'?"
                            );
                        } else {
                            warn!("Unknown variable 'node.inventory[{k1}]'");
                        }
                    } else if s.len() >= 2 {
                        // Check the second level
                        let key1_v = inventory
                            .iter()
                            .find(|i| {
                                i.as_mapping()
                                    .map(|m| m.keys().next().unwrap().as_str().unwrap() == k1)
                                    .unwrap_or(false)
                            })
                            .unwrap();

                        let key1_seq = key1_v
                            .as_mapping()
                            .unwrap()
                            .iter()
                            .next()
                            .unwrap()
                            .1
                            .as_sequence()
                            .unwrap();

                        let vals: Vec<&str> =
                            key1_seq.iter().map(|v| v.as_str().unwrap()).collect();

                        // Allow specifying only the first level to access the object
                        if let Some(Expression::Scalar(k2)) = s.get(1) {
                            if !vals.contains(&k2.as_str()) {
                                if let Some(prop) = did_you_mean(k2, vals) {
                                    warn!(
                                        "Unknown variable 'node.inventory[{k1}][{k2}]', did you mean '{prop}'?"
                                    );
                                } else {
                                    warn!("Unknown variable 'node.inventory[{k1}][{k2}]'");
                                }
                            }
                        }
                    }
                }
                for e in s {
                    e.lint()?;
                }
            }
            Self::GlobalParameter(p) => p.lint()?,
            Self::Sys(s) => {
                let key = &s[0];
                if let Expression::Scalar(k) = key {
                    let vals: Vec<&str> = known_vars
                        .get("sys")
                        .unwrap()
                        .as_sequence()
                        .unwrap()
                        .iter()
                        .map(|v| v.as_str().unwrap())
                        .collect();
                    if !vals.contains(&k.as_str()) {
                        if let Some(prop) = did_you_mean(k, vals) {
                            warn!("Unknown variable 'sys.{k}', did you mean '{prop}'?");
                        } else {
                            warn!("Unknown variable 'sys.{k}'");
                        }
                    }
                }
                for e in s {
                    e.lint()?;
                }
            }
            Self::Const(key) => {
                if let Expression::Scalar(k) = key.as_ref() {
                    let vals: Vec<&str> = known_vars
                        .get("const")
                        .unwrap()
                        .as_sequence()
                        .unwrap()
                        .iter()
                        .map(|v| v.as_str().unwrap())
                        .collect();
                    if !vals.contains(&k.as_str()) {
                        if let Some(prop) = did_you_mean(k, vals) {
                            warn!("Unknown variable 'const.{k}', did you mean '{prop}'?");
                        } else {
                            warn!("Unknown variable 'const.{k}'");
                        }
                    }
                }
                key.lint()?;
            }
            Self::NcfConst(key) => {
                if let Expression::Scalar(k) = key.as_ref() {
                    let vals: Vec<&str> = known_vars
                        .get("ncf_const")
                        .unwrap()
                        .as_sequence()
                        .unwrap()
                        .iter()
                        .map(|v| v.as_str().unwrap())
                        .collect();
                    if !vals.contains(&k.as_str()) {
                        if let Some(prop) = did_you_mean(k, vals) {
                            warn!("Unknown variable 'ncf_const.{k}', did you mean '{prop}'?");
                        } else {
                            warn!("Unknown variable 'ncf_const.{k}'");
                        }
                    }
                }
                key.lint()?;
            }
            Self::Empty => (),
        }
        Ok(())
    }

    pub fn fmt(&self, target: Target) -> String {
        match self {
            Self::Sequence(s) => match target {
                Target::Unix => s
                    .iter()
                    .map(|i| i.fmt(target))
                    .collect::<Vec<String>>()
                    .join(""),
                Target::Windows => s
                    .iter()
                    .map(|i| match i {
                        Expression::Scalar(_) => format!("@'\n{}\n'@", i.fmt(target)),
                        _ => format!("({})", i.fmt(target)),
                    })
                    .collect::<Vec<String>>()
                    .join(" + "),
            },
            Self::GenericVar(e) => match target {
                Target::Unix => {
                    if e.len() == 1 {
                        format!("${{{}}}", e[0].fmt(target))
                    } else {
                        let keys = e
                            .iter()
                            .skip(1)
                            .map(|i| i.fmt(target))
                            .collect::<Vec<String>>()
                            .join("][");
                        format!("${{{}[{}]}}", e[0].fmt(target), keys)
                    }
                }
                Target::Windows => {
                    let mut x = "@'\nvars.".to_string();
                    for (index, element) in e.iter().enumerate() {
                        match element {
                            Expression::Scalar(_) => {
                                x.push_str(&element.fmt(target));
                                if index == e.len() - 1 {
                                    x.push_str("\n'@")
                                } else {
                                    x.push('.')
                                }
                            }
                            _ => {
                                x.push_str(&format!("\n'@ + {}", element.fmt(target)));
                                if index != e.len() - 1 {
                                    x.push_str(" + @'\n.")
                                }
                            }
                        };
                    }
                    nustache_render(&x)
                }
            },
            Self::Scalar(s) => s.to_string(),
            Self::NodeProperty(e) => {
                let mut x = e.clone();
                x.insert(0, Expression::Scalar("node.properties".to_string()));
                Expression::GenericVar(x.to_vec()).fmt(target)
            }
            Self::NodeInventory(e) => {
                let mut x = e.clone();
                x.insert(0, Expression::Scalar("node.inventory".to_string()));
                Expression::GenericVar(x.to_vec()).fmt(target)
            }
            Self::GlobalParameter(p) => {
                let a = *p.to_owned();
                let key = a.fmt(target);
                match target {
                    Target::Unix => format!("${{rudder.parameters[{key}]}}"),
                    Target::Windows => {
                        nustache_render(&format!("{{{{rudder.parameters.{key}}}}}"))
                    }
                }
            }
            Self::Sys(e) => match target {
                Target::Unix => {
                    if e.len() == 1 {
                        format!("${{sys.{}}}", e[0].fmt(target))
                    } else {
                        let keys = e
                            .iter()
                            .skip(1)
                            .map(|i| i.fmt(target))
                            .collect::<Vec<String>>()
                            .join("][");
                        format!("${{sys.{}[{}]}}", e[0].fmt(target), keys)
                    }
                }
                Target::Windows => {
                    let mut x = e.clone();
                    x.insert(0, Expression::Scalar("sys".to_string()));
                    Expression::GenericVar(x.to_vec()).fmt(target)
                }
            },
            Self::Const(e) => match target {
                Target::Unix => {
                    let a = *e.to_owned();
                    let key = a.fmt(target);
                    format!("${{const.{key}}}")
                }
                Target::Windows => Expression::GenericVar(vec![
                    Expression::Scalar("const".to_string()),
                    *e.to_owned(),
                ])
                .fmt(target),
            },
            Self::NcfConst(e) => match target {
                Target::Unix => {
                    let a = *e.to_owned();
                    let key = a.fmt(target);
                    format!("${{ncf_const.{key}}}")
                }
                Target::Windows => Expression::GenericVar(vec![
                    Expression::Scalar("ncf_const".to_string()),
                    *e.to_owned(),
                ])
                .fmt(target),
            },
            Self::Empty => "".to_string(),
        }
    }
}

/// Parses valid expressions until eof
fn complete_expression(s: &str) -> IResult<&str, Expression> {
    let (s, e) = expression(s, false)?;
    let (s, _) = eof(s)?;
    Ok((s, e))
}

/// Parses valid expressions
///
/// `in_var`: are we inside a var context or in normal text.
fn expression(s: &str, in_var: bool) -> IResult<&str, Expression> {
    // NOTE: parser used in many0 must reject empty input
    let (s, r) = many0(alt((
        // different types of known variables
        node_properties,
        node_inventory,
        parameter,
        sys,
        const_,
        ncf_const,
        // generic var as fallback
        generic_var,
        // default is simple string
        if in_var { string } else { out_string },
    )))
    .parse(s)?;
    Ok((
        s,
        match r.len() {
            0 => Expression::Empty,
            1 => r[0].clone(),
            _ => Expression::Sequence(r),
        },
    ))
}

// Reads a non-empty string outside any evaluation, accepts isolated `[]{}$` special chars, stops on `${` (but accept if in first position
// as it means it was not parsed as a variable).
fn out_string(s: &str) -> IResult<&str, Expression> {
    let var_start = "${";

    let (s, start) = alt((tag(var_start), tag(""))).parse(s)?;
    let (s, end) = verify(
        alt((take_until(var_start), take_while(|_| true))),
        |s: &str| !s.is_empty(),
    )
    .parse(s)?;

    Ok((s, Expression::Scalar(format!("{start}{end}"))))
}

// Reads a non-empty string until beginning or end of variable
fn string(s: &str) -> IResult<&str, Expression> {
    map(
        verify(
            take_till(|c| ['[', ']', '}', '$'].contains(&c)),
            |s: &str| !s.is_empty(),
        ),
        |out: &str| Expression::Scalar(out.to_string()),
    )
    .parse(s)
}

// Reads a node property
fn generic_var(s: &str) -> IResult<&str, Expression> {
    let (s, _) = tag("${")(s)?;
    // Property name, mandatory
    let (s, name) = expression(s, true)?;
    // Keys, optional
    let (s, mut keys) = many0(key).parse(s)?;
    let (s, _) = char('}')(s)?;
    let mut res = vec![name];
    res.append(&mut keys);
    Ok((s, Expression::GenericVar(res)))
}

fn parameter(s: &str) -> IResult<&str, Expression> {
    preceded(
        tag("${rudder.parameters"),
        terminated(
            map(key, |out| Expression::GlobalParameter(Box::new(out))),
            char('}'),
        ),
    )
    .parse(s)
}

fn sys(s: &str) -> IResult<&str, Expression> {
    let (s, _) = tag("${sys.")(s)?;
    // Property name, mandatory
    let (s, name) = expression(s, true)?;
    // Keys, optional
    let (s, mut keys) = many0(key).parse(s)?;
    let (s, _) = char('}')(s)?;
    let mut res = vec![name];
    res.append(&mut keys);
    Ok((s, Expression::Sys(res)))
}

fn const_(s: &str) -> IResult<&str, Expression> {
    preceded(
        tag("${const."),
        terminated(
            map(
                |s| expression(s, true),
                |out| Expression::Const(Box::new(out)),
            ),
            char('}'),
        ),
    )
    .parse(s)
}

fn ncf_const(s: &str) -> IResult<&str, Expression> {
    preceded(
        tag("${ncf_const."),
        terminated(
            map(
                |s| expression(s, true),
                |out| Expression::NcfConst(Box::new(out)),
            ),
            char('}'),
        ),
    )
    .parse(s)
}

// Reads a node property
fn node_properties(s: &str) -> IResult<&str, Expression> {
    preceded(
        tag("${node.properties"),
        terminated(map(many1(key), Expression::NodeProperty), char('}')),
    )
    .parse(s)
}

// Reads a node inventory value
fn node_inventory(s: &str) -> IResult<&str, Expression> {
    preceded(
        tag("${node.inventory"),
        terminated(map(many1(key), Expression::NodeInventory), char('}')),
    )
    .parse(s)
}

// Reads a key in square brackets
fn key(s: &str) -> IResult<&str, Expression> {
    preceded(char('['), terminated(|s| expression(s, true), char(']'))).parse(s)
}

#[cfg(test)]
mod tests {
    use super::*;
    use pretty_assertions::assert_eq;

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
    fn it_reads_out_string() {
        let (_, out) = out_string("toto").unwrap();
        assert_eq!(out, Expression::Scalar("toto".to_string()));
        let (_, out) = out_string("toto]").unwrap();
        assert_eq!(out, Expression::Scalar("toto]".to_string()));
        let (_, out) = out_string("toto]to").unwrap();
        assert_eq!(out, Expression::Scalar("toto]to".to_string()));
        let (_, out) = out_string("toto}plop").unwrap();
        assert_eq!(out, Expression::Scalar("toto}plop".to_string()));
    }

    #[test]
    fn it_reads_expression() {
        let out: Expression = "toto".parse().unwrap();
        assert_eq!(out, Expression::Scalar("toto".to_string()));
        let out: Expression = "${toto]".parse().unwrap();
        assert_eq!(out, Expression::Scalar("${toto]".to_string()));
        let out: Expression = "${sys.host}".parse().unwrap();
        assert_eq!(
            out,
            Expression::Sys(vec![Expression::Scalar("host".to_string())])
        );
        let out: Expression = "${sys.${host}}".parse().unwrap();
        assert_eq!(
            out,
            Expression::Sys(vec![Expression::GenericVar(vec![Expression::Scalar(
                "host".to_string()
            )])])
        );
        let out: Expression = "${const.dollar}".parse().unwrap();
        assert_eq!(
            out,
            Expression::Const(Box::new(Expression::Scalar("dollar".to_string())))
        );
        let out: Expression = "${ncf_const.s}".parse().unwrap();
        assert_eq!(
            out,
            Expression::NcfConst(Box::new(Expression::Scalar("s".to_string())))
        );
        let out: Expression = "${sys.interface_flags[eth0]}".parse().unwrap();
        assert_eq!(
            out,
            Expression::Sys(vec![
                Expression::Scalar("interface_flags".to_string()),
                Expression::Scalar("eth0".to_string())
            ])
        );
        let out: Expression = "${node.inventory[hostname]}".parse().unwrap();
        assert_eq!(
            out,
            Expression::NodeInventory(vec![Expression::Scalar("hostname".to_string())])
        );
        let out: Expression = "${database.secret[password]}".parse().unwrap();
        assert_eq!(
            out,
            Expression::GenericVar(vec![
                Expression::Scalar("database.secret".to_string()),
                Expression::Scalar("password".to_string())
            ])
        );
        let out: Expression = "${S[a]_}".parse().unwrap();
        assert_eq!(out, Expression::Scalar("${S[a]_}".to_string()),);
    }

    #[test]
    fn it_reads_keys() {
        let (_, out) = key("[toto]").unwrap();
        assert_eq!(out, Expression::Scalar("toto".to_string()))
    }

    #[test]
    fn it_reads_node_properties() {
        let (_, out) = node_properties("${node.properties[toto]}").unwrap();
        assert_eq!(
            out,
            Expression::NodeProperty(vec![Expression::Scalar("toto".to_string())])
        );
        let (_, out) = node_properties("${node.properties[toto][tutu]}").unwrap();
        assert_eq!(
            out,
            Expression::NodeProperty(vec![
                Expression::Scalar("toto".to_string()),
                Expression::Scalar("tutu".to_string())
            ])
        );
        let (_, out) =
            node_properties("${node.properties[${node.properties[inner]}][tutu]}").unwrap();
        assert_eq!(
            out,
            Expression::NodeProperty(vec![
                Expression::NodeProperty(vec![Expression::Scalar("inner".to_string())]),
                Expression::Scalar("tutu".to_string()),
            ])
        );
    }

    #[test]
    fn it_reads_vcf_const_var() {
        let (_, out) = ncf_const("${ncf_const.s}").unwrap();
        assert_eq!(
            out,
            Expression::NcfConst(Box::new(Expression::Scalar("s".to_string())))
        );
    }

    #[test]
    fn it_reads_parameters() {
        let (_, out) = parameter("${rudder.parameters[plouf]}").unwrap();
        assert_eq!(
            out,
            Expression::GlobalParameter(Box::new(Expression::Scalar("plouf".to_string())))
        );
    }

    #[test]
    fn it_reads_node_inventory_vars() {
        let (_, out) = node_inventory("${node.inventory[hostname]}").unwrap();
        assert_eq!(
            out,
            Expression::NodeInventory(vec![Expression::Scalar("hostname".to_string())])
        );

        let (_, out) = node_inventory("${node.inventory[machine][machineType]}").unwrap();
        assert_eq!(
            out,
            Expression::NodeInventory(vec![
                Expression::Scalar("machine".to_string()),
                Expression::Scalar("machineType".to_string())
            ])
        );
    }

    #[test]
    fn it_formats_expressions() {
        let a: Expression = Expression::Sequence(vec![
            Expression::Scalar("/bin/true \"# ".to_string()),
            Expression::NodeInventory(vec![
                Expression::Scalar("os".to_string()),
                Expression::Scalar("fullName".to_string()),
            ]),
            Expression::Scalar("\"".to_string()),
        ]);
        assert_eq!(
            a.fmt(Target::Unix),
            "/bin/true \"# ${node.inventory[os][fullName]}\""
        );
        assert_eq!(
            a.fmt(Target::Windows),
            r###"@'
/bin/true "# 
'@ + ([Rudder.Datastate]::Render('{{{' + @'
vars.node.inventory.os.fullName
'@ + '}}}')) + @'
"
'@"###
        );

        let b: Expression = Expression::NodeProperty(vec![
            Expression::Scalar("a".to_string()),
            Expression::Scalar("b".to_string()),
        ]);
        assert_eq!(b.fmt(Target::Unix), "${node.properties[a][b]}");
        assert_eq!(
            b.fmt(Target::Windows),
            r#"[Rudder.Datastate]::Render('{{{' + @'
vars.node.properties.a.b
'@ + '}}}')"#
        );

        let c: Expression = Expression::Sys(vec![Expression::Scalar("host".to_string())]);
        assert_eq!(c.fmt(Target::Unix), "${sys.host}");
        assert_eq!(
            c.fmt(Target::Windows),
            r#"[Rudder.Datastate]::Render('{{{' + @'
vars.sys.host
'@ + '}}}')"#
        );

        let cc: Expression = Expression::Sequence(vec![Expression::Scalar("host".to_string())]);
        assert_eq!(cc.fmt(Target::Unix), "host");
        assert_eq!(
            cc.fmt(Target::Windows),
            r#"@'
host
'@"#
        );

        let d = Expression::NodeProperty(vec![Expression::Sequence(vec![
            Expression::Scalar("inner".to_string()),
            Expression::Sys(vec![Expression::Scalar("host".to_string())]),
        ])]);
        assert_eq!(d.fmt(Target::Unix), "${node.properties[inner${sys.host}]}");
        assert_eq!(
            d.fmt(Target::Windows),
            r#"[Rudder.Datastate]::Render('{{{' + @'
vars.node.properties.
'@ + @'
inner
'@ + ([Rudder.Datastate]::Render('{{{' + @'
vars.sys.host
'@ + '}}}')) + '}}}')"#
        );

        let dd = Expression::NodeProperty(vec![Expression::Sequence(vec![
            Expression::Scalar("inner".to_string()),
            Expression::Sys(vec![Expression::Sequence(vec![Expression::Scalar(
                "host".to_string(),
            )])]),
        ])]);
        assert_eq!(
            dd.fmt(Target::Windows),
            r#"[Rudder.Datastate]::Render('{{{' + @'
vars.node.properties.
'@ + @'
inner
'@ + ([Rudder.Datastate]::Render('{{{' + @'
vars.sys.
'@ + @'
host
'@ + '}}}')) + '}}}')"#
        );
        assert_eq!(dd.fmt(Target::Unix), "${node.properties[inner${sys.host}]}");

        let ee = Expression::NodeProperty(vec![
            Expression::Sequence(vec![Expression::Scalar("interfaces".to_string())]),
            Expression::Sequence(vec![Expression::Scalar("eth0".to_string())]),
        ]);
        assert_eq!(ee.fmt(Target::Unix), "${node.properties[interfaces][eth0]}");
        assert_eq!(
            ee.fmt(Target::Windows),
            r#"[Rudder.Datastate]::Render('{{{' + @'
vars.node.properties.
'@ + @'
interfaces
'@ + @'
.
'@ + @'
eth0
'@ + '}}}')"#
        );

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
            e.fmt(Target::Windows),
            r#"[Rudder.Datastate]::Render('{{{' + @'
vars.node.properties.
'@ + ([Rudder.Datastate]::Render('{{{' + @'
vars.node.properties.
'@ + @'
inner
'@ + ([Rudder.Datastate]::Render('{{{' + @'
vars.sys.
'@ + @'
host
'@ + '}}}')) + ([Rudder.Datastate]::Render('{{{' + @'
vars.sys.
'@ + @'
interfaces
'@ + @'
.
'@ + @'
eth0
'@ + '}}}')) + '}}}')) + @'
.
'@ + @'
tutu
'@ + '}}}')"#
        );
        assert_eq!(
            e.fmt(Target::Unix),
            "${node.properties[${node.properties[inner${sys.host}${sys.interfaces[eth0]}]}][tutu]}"
                .to_string()
        );
        let e = Expression::Sequence(vec![Expression::Sys(vec![Expression::Sequence(vec![
            Expression::GenericVar(vec![Expression::Sequence(vec![Expression::Scalar(
                "host".to_string(),
            )])]),
        ])])]);
        assert_eq!(
            e.fmt(Target::Windows),
            r#"([Rudder.Datastate]::Render('{{{' + @'
vars.sys.
'@ + ([Rudder.Datastate]::Render('{{{' + @'
vars.
'@ + @'
host
'@ + '}}}')) + '}}}'))"#
                .to_string()
        );
        let e = Expression::GenericVar(vec![
            Expression::Scalar("bundle.plouf".to_string()),
            Expression::Scalar("key".to_string()),
        ]);
        assert_eq!(
            e.fmt(Target::Windows),
            r#"[Rudder.Datastate]::Render('{{{' + @'
vars.bundle.plouf.key
'@ + '}}}')"#
                .to_string()
        );
        assert_eq!(e.fmt(Target::Unix), "${bundle.plouf[key]}".to_string());

        let f = Expression::Sequence(vec![
            Expression::Scalar("bundle.plouf is ".to_string()),
            Expression::GenericVar(vec![Expression::Scalar("bundle.plouf".to_string())]),
        ]);
        assert_eq!(
            f.fmt(Target::Windows),
            r#"@'
bundle.plouf is 
'@ + ([Rudder.Datastate]::Render('{{{' + @'
vars.bundle.plouf
'@ + '}}}'))"#
                .to_string()
        );
    }

    #[test]
    fn it_suggests_values() {
        let values = ["Alexis", "Félix", "Vincent"];
        assert_eq!(did_you_mean("Félou", values).unwrap(), "Félix");
        assert_eq!(did_you_mean("Vince", values).unwrap(), "Vincent");
        assert!(did_you_mean("GLORG", values).is_none());
    }

    #[test]
    fn it_simplifies_expressions_using_technique_params() {
        let target = Target::Windows;
        let t_id = "technique_id";
        let technique_params = vec![technique::Parameter {
            name: "param1".to_string(),
            description: None,
            documentation: None,
            id: technique::Id::from_str("param_id").unwrap(),
            _type: technique::ParameterType::String,
            constraints: technique::Constraints {
                allow_empty: false,
                regex: None,
                select: None,
                password_hashes: None,
            },
            default: None,
        }];
        // ${bundle.plouf[key]}
        let e = Expression::GenericVar(vec![
            Expression::Scalar("bundle.plouf".to_string()),
            Expression::Scalar("key".to_string()),
        ]);
        assert_eq!(
            e.clone()
                .force_long_name_for_technique_params(target, t_id, technique_params.clone())
                .unwrap(),
            e
        );

        // ${bundle.plouf}
        let e = Expression::GenericVar(vec![Expression::Scalar("bundle.plouf".to_string())]);
        assert_eq!(
            e.clone()
                .force_long_name_for_technique_params(target, t_id, technique_params.clone())
                .unwrap(),
            e
        );

        // ${param1} -> ${technique_id.param1}
        let e = Expression::GenericVar(vec![Expression::Scalar("param1".to_string())]);
        assert_eq!(
            e.force_long_name_for_technique_params(target, t_id, technique_params.clone())
                .unwrap(),
            Expression::GenericVar(vec![
                Expression::Scalar("technique_id".to_string()),
                Expression::Scalar("param1".to_string())
            ])
        );
        // ${singleWord} -> ${singleWord}
        let e = Expression::GenericVar(vec![Expression::Scalar("singleWord".to_string())]);
        assert_eq!(
            e.force_long_name_for_technique_params(target, t_id, technique_params.clone())
                .unwrap(),
            Expression::GenericVar(vec![Expression::Scalar("singleWord".to_string()),])
        );
        // ${notTechniqueParam.param1} -> ${notTechniqueParam.param1}
        let e = Expression::GenericVar(vec![
            Expression::Scalar("notTechniqueParam".to_string()),
            Expression::Scalar("param1".to_string()),
        ]);
        assert_eq!(
            e.force_long_name_for_technique_params(target, t_id, technique_params.clone())
                .unwrap(),
            Expression::GenericVar(vec![
                Expression::Scalar("notTechniqueParam".to_string()),
                Expression::Scalar("param1".to_string()),
            ])
        );
        // ${param11} -> ${param11}
        let e = Expression::GenericVar(vec![Expression::Scalar("param11".to_string())]);
        assert_eq!(
            e.force_long_name_for_technique_params(target, t_id, technique_params.clone())
                .unwrap(),
            Expression::GenericVar(vec![Expression::Scalar("param11".to_string()),])
        );
    }

    #[test]
    fn it_lints_node_inventory_vars() {
        let e = Expression::from_str("${node.inventory[hostname]}").unwrap();
        assert!(e.lint().is_ok());

        let e = Expression::from_str("${node.inventory[machine][machineType]}").unwrap();
        assert!(e.lint().is_ok());
    }
}
