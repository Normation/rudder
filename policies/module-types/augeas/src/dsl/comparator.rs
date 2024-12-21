// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use anyhow::bail;
use anyhow::{Error, Result};
use bytesize::ByteSize;
use regex::Regex;
use std::fmt::Debug;
use std::num::{ParseFloatError, ParseIntError};
use std::str::FromStr;

#[derive(Debug, Clone)]
pub enum NumComparator {
    /// Greater than.
    GreaterThan,
    /// Greater than or equal.
    GreaterThanOrEqual,
    /// Not equal, for values parsed as numbers.
    NotEqual,
    /// Equal, for values parsed as numbers.
    Equal,
    /// Less than or equal.
    LessThanOrEqual,
    /// Less than.
    LessThan,
}

#[derive(Debug, Clone)]
pub struct NumericComparison {
    pub comparator: NumComparator,
    pub value: Number,
}

#[derive(Debug, Clone)]
pub enum StrComparison {
    /// Match a regex.
    MatchRegex(Regex),
    /// Not match a regex.
    NotMatchRegex(Regex),
    /// Not equal.
    StrNotEqual(String),
    /// Equal.
    StrEqual(String),
}

#[derive(Debug, Clone)]
pub enum Comparison {
    Num(NumericComparison),
    Str(StrComparison),
}

#[derive(PartialEq, Copy, Clone)]
pub enum Number {
    Int(isize),
    Float(f32),
    Bytes(ByteSize),
}

impl Debug for Number {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Number::Int(n) => write!(f, "int:{}", n),
            Number::Float(n) => write!(f, "float:{}", n),
            Number::Bytes(n) => write!(f, "bytes:{}", n),
        }
    }
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
                    Err(_) => {
                        let num_bytes: Result<ByteSize, String> = s.parse();
                        match num_bytes {
                            Ok(n) => Ok(Number::Bytes(n)),
                            Err(_) => bail!("Invalid number '{}'", s),
                        }
                    }
                }
            }
        }
    }
}

// Generic number comparison.
//
// "a operator b"
#[macro_use]
macro_rules! match_comparator {
    ($a:expr, $b:expr, $comparator:expr) => {
        match $comparator {
            NumComparator::GreaterThan => Ok($a > $b),
            NumComparator::GreaterThanOrEqual => Ok($a >= $b),
            NumComparator::NotEqual => Ok($a != $b),
            NumComparator::Equal => Ok($a == $b),
            NumComparator::LessThanOrEqual => Ok($a <= $b),
            NumComparator::LessThan => Ok($a < $b),
        }
    };
}

impl NumericComparison {
    /// Compare the given value with the stored one.
    ///
    /// evaluates `value self.comparator self.value`
    fn matches(&self, value: Number) -> Result<bool> {
        match (value, self.value) {
            (Number::Int(a), Number::Int(b)) => match_comparator!(a, b, self.comparator),
            (Number::Float(a), Number::Float(b)) => match_comparator!(a, b, self.comparator),
            (Number::Int(a), Number::Float(b)) => {
                let a = a as f32;
                match_comparator!(a, b, self.comparator)
            }
            (Number::Float(a), Number::Int(b)) => {
                let b = b as f32;
                match_comparator!(a, b, self.comparator)
            }
            (Number::Bytes(a), Number::Bytes(b)) => match_comparator!(a, b, self.comparator),
            // When comparing int and bytes, convert the int to bytes
            (Number::Int(a), Number::Bytes(b)) if a >= 0 => {
                let a = ByteSize(a as u64);
                match_comparator!(a, b, self.comparator)
            }
            (Number::Bytes(a), Number::Int(b)) if b >= 0 => {
                let b = ByteSize(b as u64);
                match_comparator!(a, b, self.comparator)
            }
            _ => bail!(
                "Invalid comparison between numbers '{:?}' and '{:?}'",
                self.value,
                value
            ),
        }
    }
}

impl StrComparison {
    pub fn matches(&self, value: &str) -> Result<bool> {
        match self {
            StrComparison::MatchRegex(re) => Ok(re.is_match(value)),
            StrComparison::NotMatchRegex(re) => Ok(!re.is_match(value)),
            StrComparison::StrNotEqual(s) => Ok(value != s),
            StrComparison::StrEqual(s) => Ok(value == s),
        }
    }
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

        let n: Number = "42B".parse().unwrap();
        assert_eq!(n, Number::Bytes(ByteSize::b(42)));

        let n: Number = "42KB".parse().unwrap();
        assert_eq!(n, Number::Bytes(ByteSize::kb(42)));
    }

    #[test]
    fn test_numeric_comparison_int() {
        let c = NumericComparison {
            comparator: NumComparator::GreaterThan,
            value: Number::Int(42),
        };

        let r = c.matches(Number::Int(41)).unwrap();
        assert!(!r);

        let r = c.matches(Number::Int(42)).unwrap();
        assert!(!r);

        let r = c.matches(Number::Int(43)).unwrap();
        assert!(r);

        let r = c.matches(Number::Float(42.0)).unwrap();
        assert!(!r);

        let r = c.matches(Number::Float(42.1)).unwrap();
        assert!(r);

        let r = c.matches(Number::Bytes(ByteSize::b(42))).unwrap();
        assert!(!r);

        let r = c.matches(Number::Bytes(ByteSize::b(43))).unwrap();
        assert!(r);
    }

    #[test]
    fn test_numeric_comparison_float() {
        let c = NumericComparison {
            comparator: NumComparator::GreaterThan,
            value: Number::Float(42.0),
        };

        let r = c.matches(Number::Int(41)).unwrap();
        assert!(!r);

        let r = c.matches(Number::Int(42)).unwrap();
        assert!(!r);

        let r = c.matches(Number::Int(43)).unwrap();
        assert!(r);

        let r = c.matches(Number::Float(42.0)).unwrap();
        assert!(!r);

        let r = c.matches(Number::Float(42.1)).unwrap();
        assert!(r);

        let r = c.matches(Number::Bytes(ByteSize::b(42)));
        assert!(r.is_err());
    }

    #[test]
    fn test_str_comparison_matches() {
        let c = StrComparison::StrEqual("foo".to_string());
        let r = c.matches("foo").unwrap();
        assert!(r);

        let r = c.matches("bar").unwrap();
        assert!(!r);

        let c = StrComparison::StrNotEqual("foo".to_string());
        let r = c.matches("foo").unwrap();
        assert!(!r);

        let r = c.matches("bar").unwrap();
        assert!(r);

        let c = StrComparison::MatchRegex(Regex::new(r"^\d+$").unwrap());
        let r = c.matches("42").unwrap();
        assert!(r);

        let r = c.matches("foo").unwrap();
        assert!(!r);

        let c = StrComparison::NotMatchRegex(Regex::new(r"^\d+$").unwrap());
        let r = c.matches("42").unwrap();
        assert!(!r);

        let r = c.matches("foo").unwrap();
        assert!(r);
    }
}
