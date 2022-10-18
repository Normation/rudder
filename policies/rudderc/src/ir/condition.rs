// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2022 Normation SAS

use std::{fmt, str::FromStr};

use anyhow::{bail, Error};
use serde::{de, Deserialize, Deserializer, Serialize};

/// Valid condition
///
/// Simple representation to allow trivial optimizations. At some point we might add
/// a real condition evaluator.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(untagged)]
pub enum Condition {
    /// a.k.a. "true" / "any"
    #[serde(rename = "true")]
    Defined,
    /// a.k.a. "false" / "!any"
    #[serde(rename = "false")]
    NotDefined,
    /// Condition expression that will be evaluated at runtime
    Expression(String),
}

impl Condition {
    pub fn and(&self, c: &Condition) -> Condition {
        match (self, c) {
            (_, Condition::NotDefined) => Condition::NotDefined,
            (Condition::NotDefined, _) => Condition::NotDefined,
            (Condition::Defined, Condition::Defined) => Condition::Defined,
            (Condition::Expression(e1), Condition::Expression(e2)) => {
                Condition::Expression(format!("({e1}).({e2})"))
            }
            (Condition::Expression(e), Condition::Defined) => Condition::Expression(e.clone()),
            (Condition::Defined, Condition::Expression(e)) => Condition::Expression(e.clone()),
        }
    }
}

impl Default for Condition {
    fn default() -> Self {
        Self::Defined
    }
}

impl AsRef<str> for Condition {
    fn as_ref(&self) -> &str {
        match self {
            Self::Defined => "true",
            Self::NotDefined => "false",
            Self::Expression(e) => e,
        }
    }
}

impl fmt::Display for Condition {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.as_ref())
    }
}

impl From<Condition> for String {
    fn from(c: Condition) -> Self {
        c.as_ref().to_string()
    }
}

impl FromStr for Condition {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        fn valid_char(c: char) -> bool {
            // Ideally, we could parse and validate condition expressions.
            // For now, let's only check for disallowed chars instead.
            let valid_chars = ['"', '$', '{', '}', '|', '&', ' '];
            c.is_ascii_alphanumeric() || valid_chars.contains(&c)
        }

        // A hack to handle "(any)" like conditions
        let unparenthesized = s.replace(['(', ')'], "");

        Ok(if ["true", "any"].contains(&unparenthesized.as_str()) {
            Self::Defined
        } else if ["false", "!any", "!true"].contains(&unparenthesized.as_str()) {
            Self::NotDefined
        } else if s.chars().all(valid_char) {
            Condition::Expression(s.to_string())
        } else {
            bail!("Invalid id: {}", s)
        })
    }
}

impl<'de> Deserialize<'de> for Condition {
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where
        D: Deserializer<'de>,
    {
        let s = String::deserialize(deserializer)?;
        FromStr::from_str(&s).map_err(de::Error::custom)
    }
}
