// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use anyhow::{anyhow, bail};
use anyhow::{Error, Result};
use regex::Regex;
use std::num::{ParseFloatError, ParseIntError};
use std::str::FromStr;

pub type MatchPath = String;

/*

ifonly:
    get <AUGEAS_PATH> <COMPARATOR> <STRING>
    values <MATCH_PATH> include <STRING>
    values <MATCH_PATH> not_include <STRING>
    values <MATCH_PATH> == <AN_ARRAY>
    values <MATCH_PATH> != <AN_ARRAY>
    match <MATCH_PATH> size <COMPARATOR> <INT>
    match <MATCH_PATH> include <STRING>
    match <MATCH_PATH> not_include <STRING>
    match <MATCH_PATH> == <AN_ARRAY>
    match <MATCH_PATH> != <AN_ARRAY>

where:
    AUGEAS_PATH is a valid path scoped by the context
    MATCH_PATH is a valid match syntax scoped by the context
    COMPARATOR is one of >, >=, !=, ==, <=, or <
      ~ for regex match, !~ for regex not match
    STRING is a string
    INT is a number
    AN_ARRAY is in the form ['a string', 'another']
*/

#[derive(Debug, Clone)]
pub enum Comparator {
    GreaterThan(Number),
    GreaterThanOrEqual(Number),
    NotEqual(Number),
    Equal(Number),
    LessThanOrEqual(Number),
    LessThan(Number),
    MatchRegex(Regex),
    NotMatchRegex(Regex),
    StrNotEqual(String),
    StrEqual(String),
}

#[derive(Debug, PartialEq, Copy, Clone)]
pub enum Number {
    Int(isize),
    Float(f32),
}

impl FromStr for Number {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        let num_int: Result<isize, ParseIntError> = s.parse();
        match num_int {
            Ok(n) => Ok(Number::Int(n)),
            Err(_) => {
                let num_float: Result<f32, ParseFloatError> = s.parse();
                match num_float {
                    Ok(n) => Ok(Number::Float(n)),
                    Err(_) => Err(anyhow!("Could not parse number '{}'", s)),
                }
            }
        }
    }
}

impl Comparator {
    fn matches_int(&self, value: isize) -> Result<bool> {
        Ok(match self {
            Comparator::GreaterThan(n) => match n {
                Number::Int(n) => value > *n,
                Number::Float(n) => value as f32 > *n,
            },
            Comparator::GreaterThanOrEqual(n) => match n {
                Number::Int(n) => value >= *n,
                Number::Float(n) => value as f32 >= *n,
            },
            Comparator::NotEqual(n) => match n {
                Number::Int(n) => value != *n,
                Number::Float(n) => value as f32 != *n,
            },
            Comparator::Equal(n) => match n {
                Number::Int(n) => value == *n,
                Number::Float(n) => value as f32 == *n,
            },
            Comparator::LessThanOrEqual(n) => match n {
                Number::Int(n) => value <= *n,
                Number::Float(n) => value as f32 <= *n,
            },
            Comparator::LessThan(n) => match n {
                Number::Int(n) => value < *n,
                Number::Float(n) => (value as f32) < *n,
            },
            _ => bail!("Invalid comparator for integer number"),
        })
    }

    fn matches_float(&self, value: f32) -> Result<bool> {
        Ok(match self {
            Comparator::GreaterThan(n) => match n {
                Number::Int(n) => value > *n as f32,
                Number::Float(n) => value > *n,
            },
            Comparator::GreaterThanOrEqual(n) => match n {
                Number::Int(n) => value >= *n as f32,
                Number::Float(n) => value >= *n,
            },
            Comparator::NotEqual(n) => match n {
                Number::Int(n) => value != *n as f32,
                Number::Float(n) => value != *n,
            },
            Comparator::Equal(n) => match n {
                Number::Int(n) => value == *n as f32,
                Number::Float(n) => value == *n,
            },
            Comparator::LessThanOrEqual(n) => match n {
                Number::Int(n) => value <= *n as f32,
                Number::Float(n) => value <= *n,
            },
            Comparator::LessThan(n) => match n {
                Number::Int(n) => value < *n as f32,
                Number::Float(n) => value < *n,
            },
            _ => bail!("Invalid comparator for float number"),
        })
    }

    pub fn matches(&self, value: &str) -> Result<bool> {
        match self {
            Comparator::MatchRegex(re) => Ok(re.is_match(value)),
            Comparator::NotMatchRegex(re) => Ok(!re.is_match(value)),
            Comparator::StrNotEqual(s) => Ok(value != s),
            Comparator::StrEqual(s) => Ok(value == s),
            _ => {
                let value: Number = value.parse()?;
                match value {
                    Number::Int(value) => self.matches_int(value),
                    Number::Float(value) => self.matches_float(value),
                }
            }
        }
    }
}

pub enum Check {
    Get(String, Comparator, String),
    ValuesInclude(String, String),
    ValuesNotInclude(String, String),
    ValuesEqual(String, Vec<String>),
    ValuesNotEqual(String, Vec<String>),
    MatchSize(String, String, usize),
    MatchInclude(String, String),
    MatchNotInclude(String, String),
    MatchEqual(String, Vec<String>),
    MatchNotEqual(String, Vec<String>),
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_number_from_str() {
        let n: Number = "42".parse().unwrap();
        assert_eq!(n, Number::Int(42));

        let n: Number = "42.0".parse().unwrap();
        assert_eq!(n, Number::Float(42.0));

        let n: Number = "42.5".parse().unwrap();
        assert_eq!(n, Number::Float(42.5));

        let r: Result<Number, Error> = "42.5.0".parse();
        assert!(r.is_err());
    }

    #[test]
    fn test_comparator_matches_int() {
        let c = Comparator::GreaterThan(Number::Int(42));
        assert!(c.matches_int(43).unwrap());
        assert!(!c.matches_int(42).unwrap());
        assert!(!c.matches_int(41).unwrap());

        let c = Comparator::GreaterThanOrEqual(Number::Int(42));
        assert!(c.matches_int(43).unwrap());
        assert!(c.matches_int(42).unwrap());
        assert!(!c.matches_int(41).unwrap());

        let c = Comparator::NotEqual(Number::Int(42));
        assert!(c.matches_int(43).unwrap());
        assert!(!c.matches_int(42).unwrap());
        assert!(c.matches_int(41).unwrap());

        let c = Comparator::Equal(Number::Int(42));
        assert!(!c.matches_int(43).unwrap());
        assert!(c.matches_int(42).unwrap());
        assert!(!c.matches_int(41).unwrap());

        let c = Comparator::LessThanOrEqual(Number::Int(42));
        assert!(!c.matches_int(43).unwrap());
        assert!(c.matches_int(42).unwrap());
        assert!(c.matches_int(41).unwrap());

        let c = Comparator::LessThan(Number::Int(42));
        assert!(!c.matches_int(43).unwrap());
        assert!(!c.matches_int(42).unwrap());
        assert!(c.matches_int(41).unwrap());
    }

    #[test]
    fn test_comparator_matches_float() {
        let c = Comparator::GreaterThan(Number::Float(42.0));
        assert!(c.matches_float(43.0).unwrap());
        assert!(!c.matches_float(42.0).unwrap());
        assert!(!c.matches_float(41.0).unwrap());

        let c = Comparator::GreaterThanOrEqual(Number::Float(42.0));
        assert!(c.matches_float(43.0).unwrap());
        assert!(c.matches_float(42.0).unwrap());
        assert!(!c.matches_float(41.0).unwrap());

        let c = Comparator::NotEqual(Number::Float(42.0));
        assert!(c.matches_float(43.0).unwrap());
        assert!(!c.matches_float(42.0).unwrap());
        assert!(c.matches_float(41.0).unwrap());

        let c = Comparator::Equal(Number::Float(42.0));
        assert!(!c.matches_float(43.0).unwrap());
        assert!(c.matches_float(42.0).unwrap());
        assert!(!c.matches_float(41.0).unwrap());

        let c = Comparator::LessThanOrEqual(Number::Float(42.0));
        assert!(!c.matches_float(43.0).unwrap());
        assert!(c.matches_float(42.0).unwrap());
        assert!(c.matches_float(41.0).unwrap());

        let c = Comparator::LessThan(Number::Float(42.0));
        assert!(!c.matches_float(43.0).unwrap());
        assert!(!c.matches_float(42.0).unwrap());
        assert!(c.matches_float(41.0).unwrap());
    }

    #[test]
    fn test_comparator_match() {
        let c = Comparator::MatchRegex(Regex::new(r"^\d+$").unwrap());
        assert!(c.matches("42").unwrap());
        assert!(!c.matches("42.0").unwrap());

        let c = Comparator::NotMatchRegex(Regex::new(r"^\d+$").unwrap());
        assert!(!c.matches("42").unwrap());
        assert!(c.matches("42.0").unwrap());

        let c = Comparator::StrNotEqual("42.0".to_string());
        assert!(!c.matches("42.0").unwrap());
        assert!(c.matches("42.00").unwrap());

        let c = Comparator::StrEqual("42.0".to_string());
        assert!(c.matches("42.0").unwrap());
        assert!(!c.matches("42").unwrap());
        assert!(!c.matches("42.00").unwrap());

        let c = Comparator::GreaterThan(Number::Int(42));
        assert!(c.matches("43").unwrap());
        assert!(!c.matches("42").unwrap());
        assert!(!c.matches("41").unwrap());

        let c = Comparator::GreaterThanOrEqual(Number::Int(42));
        assert!(c.matches("43").unwrap());
        assert!(c.matches("42").unwrap());
        assert!(!c.matches("41").unwrap());

        let c = Comparator::NotEqual(Number::Int(42));
        assert!(c.matches("43").unwrap());
        assert!(!c.matches("42").unwrap());
        assert!(c.matches("41").unwrap());

        let c = Comparator::Equal(Number::Int(42));
        assert!(!c.matches("43").unwrap());
        assert!(c.matches("42").unwrap());
        assert!(!c.matches("41").unwrap());

        let c = Comparator::LessThanOrEqual(Number::Int(42));
        assert!(!c.matches("43").unwrap());
        assert!(c.matches("42").unwrap());
        assert!(c.matches("41").unwrap());

        let c = Comparator::LessThan(Number::Int(42));
        assert!(!c.matches("43").unwrap());
        assert!(!c.matches("42").unwrap());
        assert!(c.matches("41").unwrap());
    }
}
