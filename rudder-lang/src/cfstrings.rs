// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use crate::error::*;
use nom::{
    branch::alt, bytes::complete::*, character::complete::*, combinator::*, multi::many1,
    sequence::*, IResult,
};
use std::str;

/// Canonify a string the same way cfengine does
pub fn canonify(input: &str) -> String {
    let s = input
        .as_bytes()
        .iter()
        .map(|x| {
            if x.is_ascii_alphanumeric() || *x == b'_' {
                *x
            } else {
                b'_'
            }
        })
        .collect::<Vec<u8>>();
    std::str::from_utf8(&s)
        .unwrap_or_else(|_| panic!("Canonify failed on {}", input))
        .to_owned()
}

#[derive(Clone)]
pub struct CFVariable {
    ns: Option<String>,
    name: String,
}

fn parse_variable(i: &str) -> IResult<&str, CFVariable> {
    map(
        tuple((
            opt(map(
                terminated(
                    take_while1(|c: char| c.is_alphanumeric() || (c == '_')),
                    tag("."),
                ),
                |x: &str| x.into(),
            )),
            map(
                take_while1(|c: char| c.is_alphanumeric() || (c == '_')),
                |x: &str| x.into(),
            ),
        )),
        |(ns, name)| CFVariable { ns, name },
    )(i)
}

#[derive(Clone)]
pub enum CFStringElt {
    Static(String),       // static content
    Variable(CFVariable), // variable name
}

impl CFStringElt {
    #[allow(dead_code)]
    fn to_string(&self) -> Result<String> {
        Ok(match self {
            CFStringElt::Static(s) => s.to_string(),
            CFStringElt::Variable(v) => {
                match &v.ns {
                    None => v.name.clone(), // a parameter
                    Some(ns) => match ns.as_ref() {
                        "const" => (match v.name.as_ref() {
                            "dollar" => "$",
                            "dirsep" => "/",
                            "endl" => "\\n",
                            "n" => "\\n",
                            "r" => "\\r",
                            "t" => "\\t",
                            _ => {
                                return Err(Error::new(format!(
                                    "Unknown constant '{}.{}'",
                                    ns, v.name
                                )))
                            }
                        })
                        .into(),
                        "sys" => {
                            return Err(Error::new(format!(
                                "Not implemented variable namespace sys '{}.{}'",
                                ns, v.name
                            )))
                        }
                        "this" => {
                            return Err(Error::new(format!(
                                "Unsupported variable namespace this '{}.{}'",
                                ns, v.name
                            )))
                        }
                        ns => format!("${{{}.{}}}", ns, v.name),
                    },
                }
                // TODO
                // - array -> ?
                // - list -> ?
            }
        })
    }
}

pub fn parse_string(i: &str) -> IResult<&str, Vec<CFStringElt>> {
    // There is a rest inside so this just serve as a guard
    all_consuming(alt((
        many1(alt((
            // variable ${}
            map(
                delimited(tag("${"), parse_variable, tag("}")),
                CFStringElt::Variable,
            ),
            // constant
            map(take_until("$"), |s: &str| CFStringElt::Static(s.into())),
            // end of string
            map(
                preceded(
                    peek(anychar), // do no take rest if we are already at the end
                    rest,
                ),
                |s: &str| CFStringElt::Static(s.into()),
            ),
        ))),
        // empty string
        value(vec![CFStringElt::Static("".into())], not(anychar)),
    )))(i)
}
