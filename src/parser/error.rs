#![macro_use]

use nom::error::*;

/// This is the only error type that should be returned by the main parser.
/// It is an error that is suitable to give to the user as opposed to nom error that are suitable
/// for the developer.
/// Sub parsers may mix error types from nom or from finalerror
/// So this is a generir error type that must implement ParseError
#[derive(Debug, PartialEq)]
pub enum PError<I> {
    Nom(VerboseError<I>),
    NomTest(String), // should not be use outside of tests
    InvalidFormat,
    InvalidName(I, I),                // name of, at
    UnexpectedToken(&'static str, I), // expected, at
    UnterminatedDelimiter(I, I),      // first delimiter, expected place
}

impl<I> ParseError<I> for PError<I> {
    fn from_error_kind(input: I, kind: ErrorKind) -> Self {
        PError::Nom(VerboseError::from_error_kind(input, kind))
    }

    fn append(input: I, kind: ErrorKind, other: Self) -> Self {
        match other {
            PError::Nom(e) => PError::Nom(VerboseError::append(input, kind, e)),
            x => x,
        }
    }
}

macro_rules! or_fail (
    ($call:expr, $err:expr) => (
        {
            let x: Result<_> = $call;
            match x {
                Err(_) => return Err(Err::Failure($err)),
                Ok(x) => x,
            }
        }
    )
);
