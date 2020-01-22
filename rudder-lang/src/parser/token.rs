// SPDX-License-Identifier: GPL-3.0-only

use nom_locate::LocatedSpanEx;
use std::fmt;
use std::hash::{Hash, Hasher};
use std::ops::Deref;

/// All parsers take PInput objects
/// All input are Located str
/// Use the pinput function to create one
pub type PInput<'src> = LocatedSpanEx<&'src str, &'src str>;

/// All parser output are token based.
/// A token contains a string pointer but also the original input position
/// and the name of the file it has been extracted from.
/// A token behave like &str and has many useful traits.
/// It has copy for convenient use.
#[derive(Debug, Copy, Clone)]
//#[derive(Copy, Clone)]
pub struct Token<'src> {
    val: LocatedSpanEx<&'src str, &'src str>,
}

impl<'src> Token<'src> {
    /// Create a "fake" token from a string and a file name
    /// It won't have a position
    pub fn new(name: &'src str, input: &'src str) -> Self {
        Token {
            val: LocatedSpanEx::new_extra(input, name),
        }
    }

    /// Format a token position for compiler output (file name and position included)
    pub fn position_str(&self) -> String {
        match self.val.offset {
            0 => self.val.extra.to_owned(),
            _ => format!(
                "{}:{}:{}",
                self.val.extra.to_string(),
                self.val.line,
                self.val.get_utf8_column(),
            ),
        }
    }

    /// Extract the string part of the token
    pub fn fragment(&self) -> &'src str {
        &self.val.fragment
    }

    /// Extract the file name of the token
    pub fn file(&self) -> &'src str {
        &self.val.extra
    }
}

/// Convert from str (lossy, no file name nor position, use in terse tests only)
impl<'src> From<&'src str> for Token<'src> {
    fn from(input: &'src str) -> Self {
        Token {
            val: LocatedSpanEx::new_extra(input, ""),
        }
    }
}

// uncomment to make token debug prints shorter
//impl<'src> fmt::Debug for Token<'src> {
//    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
//        //write!(f, "{}", self)
//        write!(f, "\"{}\"", self.fragment())
//    }
//}

/// Convert from PInput (used by parsers)
impl<'src> From<PInput<'src>> for Token<'src> {
    fn from(val: PInput<'src>) -> Self {
        Token { val }
    }
}

/// Convert to PInput (used by error management)
impl<'src> From<Token<'src>> for PInput<'src> {
    fn from(t: Token<'src>) -> Self {
        t.val
    }
}

/// Token comparision only compares the string value, not the position
/// PartialEq used by tests and by Token users
impl<'src> PartialEq for Token<'src> {
    fn eq(&self, other: &Token) -> bool {
        self.val.fragment == other.val.fragment
    }
}

/// Eq by Token users, necessary to put them into for HashMaps
impl<'src> Eq for Token<'src> {}

/// Hash used by Token users as keys in HashMaps
impl<'src> Hash for Token<'src> {
    fn hash<H: Hasher>(&self, state: &mut H) {
        self.val.fragment.hash(state);
    }
}

/// Format the full token for compiler debug info
impl<'src> fmt::Display for Token<'src> {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(f, "'{}' at {}", self.val.fragment, self.position_str())
    }
}

/// Dereference token to &str
impl<'src> Deref for Token<'src> {
    type Target = &'src str;
    fn deref(&self) -> &&'src str {
        &self.val.fragment
    }
}
