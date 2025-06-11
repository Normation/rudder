// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use anyhow::{Error, Result, bail};
use bytesize::ByteSize;
use regex::Regex;
use std::{
    fmt::{Debug, Display},
    num::{ParseFloatError, ParseIntError},
    str::FromStr,
};
// https://github.com/jprochazk/garde?tab=readme-ov-file#available-validation-rules
// - password complexity checks

#[derive(Debug, Clone, PartialEq)]
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

impl NumComparator {
    /// Computes `a comparator b`.
    pub fn numeric_compare<T: PartialEq + PartialOrd>(&self, a: &T, b: &T) -> bool {
        match self {
            NumComparator::GreaterThan => a.gt(b),
            NumComparator::GreaterThanOrEqual => a.ge(b),
            NumComparator::NotEqual => a.ne(b),
            NumComparator::Equal => a.eq(b),
            NumComparator::LessThanOrEqual => a.lt(b),
            NumComparator::LessThan => a.le(b),
        }
    }
}

impl Display for NumComparator {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            NumComparator::GreaterThan => write!(f, ">"),
            NumComparator::GreaterThanOrEqual => write!(f, ">="),
            NumComparator::NotEqual => write!(f, "!="),
            NumComparator::Equal => write!(f, "=="),
            NumComparator::LessThanOrEqual => write!(f, "<="),
            NumComparator::LessThan => write!(f, "<"),
        }
    }
}

impl FromStr for NumComparator {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        match s {
            ">" => Ok(NumComparator::GreaterThan),
            ">=" => Ok(NumComparator::GreaterThanOrEqual),
            "!=" => Ok(NumComparator::NotEqual),
            "=" | "==" => Ok(NumComparator::Equal),
            "<=" => Ok(NumComparator::LessThanOrEqual),
            "<" => Ok(NumComparator::LessThan),
            _ => bail!("Invalid comparator '{}'", s),
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct NumericComparison {
    pub comparator: NumComparator,
    pub value: Number,
}

#[derive(Debug, Clone, PartialEq)]
pub enum Comparison {
    Num(NumericComparison),
    Str(StrValidation),
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
            Number::Int(n) => write!(f, "int:{n}"),
            Number::Float(n) => write!(f, "float:{n}"),
            Number::Bytes(n) => write!(f, "bytes:{n}"),
        }
    }
}

impl Display for Number {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Number::Int(n) => write!(f, "{n}"),
            Number::Float(n) => write!(f, "{n}"),
            Number::Bytes(n) => write!(f, "{n}"),
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
                            Err(_) => bail!("Invalid number '{s}'"),
                        }
                    }
                }
            }
        }
    }
}

impl NumericComparison {
    /// Compare the given value with the stored one.
    ///
    /// Evaluates `value self.comparator self.value`.
    pub(crate) fn matches(&self, value: Number) -> Result<bool> {
        Ok(match (value, self.value) {
            (Number::Int(a), Number::Int(b)) => self.comparator.numeric_compare(&a, &b),
            (Number::Float(a), Number::Float(b)) => self.comparator.numeric_compare(&a, &b),
            (Number::Int(a), Number::Float(b)) => {
                let a = a as f32;
                self.comparator.numeric_compare(&a, &b)
            }
            (Number::Float(a), Number::Int(b)) => {
                let b = b as f32;
                self.comparator.numeric_compare(&a, &b)
            }
            (Number::Bytes(a), Number::Bytes(b)) => self.comparator.numeric_compare(&a, &b),
            // When comparing int and bytes, convert the int to bytes
            (Number::Int(a), Number::Bytes(b)) if a >= 0 => {
                let a = ByteSize(a as u64);
                self.comparator.numeric_compare(&a, &b)
            }
            (Number::Bytes(a), Number::Int(b)) if b >= 0 => {
                let b = ByteSize(b as u64);
                self.comparator.numeric_compare(&a, &b)
            }
            _ => bail!(
                "Invalid comparison between numbers '{:?}' and '{:?}'",
                self.value,
                value
            ),
        })
    }
}

#[derive(Debug, Clone, PartialEq)]
pub enum StrComparator {
    Equal,
    NotEqual,
    Match,
    NotMatch,
}

impl Display for StrComparator {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            StrComparator::Equal => write!(f, "eq"),
            StrComparator::NotEqual => write!(f, "neq"),
            StrComparator::Match => write!(f, "~"),
            StrComparator::NotMatch => write!(f, "!~"),
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct StrValidation {
    pub(crate) comparator: StrComparator,
    pub(crate) value: String,
}

impl FromStr for StrComparator {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        match s {
            "eq" => Ok(StrComparator::Equal),
            "neq" => Ok(StrComparator::NotEqual),
            "~" | "=~" => Ok(StrComparator::Match),
            "!~" => Ok(StrComparator::NotMatch),
            _ => bail!("Invalid string comparator '{s}'"),
        }
    }
}

impl StrValidation {
    pub fn matches(&self, value: &str) -> bool {
        match self.comparator {
            StrComparator::Equal => value == self.value,
            StrComparator::NotEqual => value != self.value,
            _ => {
                // FIXME unwrap
                let re = Regex::new(&self.value).unwrap();
                match self.comparator {
                    StrComparator::Match => re.is_match(value),
                    StrComparator::NotMatch => !re.is_match(value),
                    _ => unreachable!(),
                }
            }
        }
    }
}

#[derive(Debug, Clone)]
pub enum RegexComparator {
    Match,
    NotMatch,
}

#[derive(Debug, Clone)]
pub struct RegexComparison {
    comparator: RegexComparator,
    re: Regex,
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
    fn test_numeric_comparison_bytes() {
        let c = NumericComparison {
            comparator: NumComparator::GreaterThan,
            value: Number::Bytes(ByteSize::b(42)),
        };

        let r = c.matches(Number::Int(41)).unwrap();
        assert!(!r);

        let r = c.matches(Number::Int(42)).unwrap();
        assert!(!r);

        let r = c.matches(Number::Int(43)).unwrap();
        assert!(r);

        let r = c.matches(Number::Float(42.0));
        assert!(r.is_err());

        let r = c.matches(Number::Bytes(ByteSize::b(42))).unwrap();
        assert!(!r);

        let r = c.matches(Number::Bytes(ByteSize::b(43))).unwrap();
        assert!(r);
    }

    #[test]
    fn test_str_comparison() {
        let c = StrValidation {
            comparator: StrComparator::Equal,
            value: "foo".to_string(),
        };

        let r = c.matches("foo");
        assert!(r);

        let r = c.matches("bar");
        assert!(!r);

        let c = StrValidation {
            comparator: StrComparator::NotEqual,
            value: "foo".to_string(),
        };

        let r = c.matches("foo");
        assert!(!r);

        let r = c.matches("bar");
        assert!(r);
    }

    #[test]
    fn test_regex_comparison() {
        let c = StrValidation {
            comparator: StrComparator::Match,
            value: r"\d+".to_string(),
        };

        let r = c.matches("34");
        assert!(r);

        let r = c.matches("bar");
        assert!(!r);

        let c = StrValidation {
            comparator: StrComparator::NotMatch,
            value: r"\d+".to_string(),
        };

        let r = c.matches("34");
        assert!(!r);

        let r = c.matches("bar");
        assert!(r);
    }
}
