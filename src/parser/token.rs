use nom::types::CompleteStr;
use nom_locate::LocatedSpan;
use std::fmt;
use std::hash::{Hash, Hasher};
use std::ops::Deref;
use std::ops::Add;

/// All parsers take PInput objects
/// All input are Located Complete str
/// Use the pinput function to create one
pub type PInput<'a> = LocatedSpan<CompleteStr<'a>, &'a str>;

/// Convenient PInput creator (alias type cannot have a constructor)
pub fn pinput<'a>(name: &'a str, input: &'a str) -> PInput<'a> {
    LocatedSpan::new(CompleteStr(input), name)
}

/// All parser output are token based.
/// A token contains a string pointer but also the original input position
/// and the name of the file it has been extracted from.
/// A token behave like &str and has many useful traits.
/// It has copy for convenient use.
#[derive(Debug, Copy, Clone)]
pub struct Token<'a> {
    val: LocatedSpan<CompleteStr<'a>, &'a str>,
}

impl<'a> Token<'a> {
    /// Create a "fake" token from a string and a file name
    /// It won't have a position
    pub fn new(name: &'a str, input: &'a str) -> Self {
        Token {
            val: LocatedSpan::new(CompleteStr(input), name),
        }
    }

    /// Format a token position for compiler output (file name and position included)
    pub fn position_str(&self) -> String {
        let (file, line, col) = self.position();
        format!("{}:{}:{}", file, line, col)
    }

    /// Retrieve the position of the token : file name, line number, column number
    pub fn position(&self) -> (String, u32, usize) {
        (
            self.val.extra.to_string(),
            self.val.line,
            self.val.get_utf8_column(),
        )
    }

    /// Extract the string part of the token
    /// TODO should not be needed because of deref
    pub fn fragment(&self) -> &'a str {
        &self.val.fragment
    }

    /// Extract the file name of the token
    pub fn file(&self) -> &'a str { &self.val.extra }
}

/// Convert from str (lossy, no file name nor position, use in terse tests only)
impl<'a> From<&'a str> for Token<'a> {
    fn from(input: &'a str) -> Self {
        Token {
            val: LocatedSpan::new(CompleteStr(input), ""),
        }
    }
}

/// Convert from PInput (used by parsers)
impl<'a> From<PInput<'a>> for Token<'a> {
    fn from(val: PInput<'a>) -> Self {
        Token { val }
    }
}

/// Token comparision only compares the string value, not the position
/// PartialEq used by tests and by Token users
impl<'a> PartialEq for Token<'a> {
    fn eq(&self, other: &Token) -> bool {
        self.val.fragment == other.val.fragment
    }
}

/// Eq by Token users, necessary to put them into for HashMaps
impl<'a> Eq for Token<'a> {}

/// Hash used by Token users as keys in HashMaps
impl<'a> Hash for Token<'a> {
    fn hash<H: Hasher>(&self, state: &mut H) {
        self.val.fragment.hash(state);
    }
}

/// Format the full token for compiler debug info
impl<'a> fmt::Display for Token<'a> {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(f, "'{}' at {}", self.val.fragment, self.position_str())
    }
}

/// Easy concatenating of tokens
impl<'a> Add<Token<'a>> for String {
    type Output = String;
    fn add(self, other: Token) -> String {
        self + *other.val.fragment
    }
}

/// Dereference token to &str
impl<'a> Deref for Token<'a> {
    type Target = &'a str;
    fn deref(&self) -> &&'a str {
        &self.val.fragment
    }
}
