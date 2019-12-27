// Copyright 2019 Normation SAS
//
// This file is part of Rudder.
//
// Rudder is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// In accordance with the terms of section 7 (7. Additional Terms.) of
// the GNU General Public License version 3, the copyright holders add
// the following Additional permissions:
// Notwithstanding to the terms of section 5 (5. Conveying Modified Source
// Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
// Public License version 3, when you create a Related Module, this
// Related Module is not considered as a part of the work and may be
// distributed under the license agreement of your choice.
// A "Related Module" means a set of sources files including their
// documentation that, without modification of the Source Code, enables
// supplementary functions or services in addition to those offered by
// the Software.
//
// Rudder is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

use super::{PInput, Token};
use crate::error::*;
use nom::error::{ErrorKind, ParseError, VerboseError};
use nom::{Err, IResult};
use std::fmt;
use colored::Colorize;

/// Result for all parser
pub type PResult<'src, O> = IResult<PInput<'src>, O, PError<PInput<'src>>>;

/// This is the only error type that should be returned by the main parser.
/// It is an error that is suitable to give to the user as opposed to nom error that are suitable
/// for the developer.
/// Sub parsers may mix error types from nom or from finalerror
/// So this is a generir error type that must implement ParseError
#[derive(Debug, PartialEq, Clone)]
pub enum PErrorKind<I> {
    Nom(VerboseError<I>),
    #[cfg(test)]
    NomTest(String), // cannot be use outside of tests
    InvalidFormat,                 // in header
    InvalidName(I),                // in identifier expressions (type of expression)
    UnexpectedToken(&'static str), // anywhere (expected token)
    UnterminatedDelimiter(I),      // after an opening delimiter (first delimiter)
    InvalidEnumExpression,         // in enum expression
    InvalidEscapeSequence,         // in string definition
    InvalidVariableReference,      // during string interpolation
    ExpectedKeyword(&'static str), // anywhere (keyword type)
    UnsupportedMetadata(I), // metadata or comments are not supported everywhere (metadata key)
}

#[derive(Debug, PartialEq, Clone)]
pub struct PError<I> {
    pub context: I,
    pub kind: PErrorKind<I>,
}

/// This trait must be implemented by the error type of a nom parser
/// Implement all method to differenciate between :
/// - nom VerboseError (stack errors)
/// - pure PError (keep last error)
impl<I: Clone> ParseError<I> for PError<I> {
    /// creates an error from the input position and an [ErrorKind]
    fn from_error_kind(input: I, kind: ErrorKind) -> Self {
        PError {
            context: input.clone(),
            kind: PErrorKind::Nom(VerboseError::from_error_kind(input, kind)),
        }
    }

    /// combines an existing error with a new one created from the input
    /// positionsition and an [ErrorKind]. This is useful when backtracking
    /// through a parse tree, accumulating error context on the way
    fn append(input: I, kind: ErrorKind, other: Self) -> Self {
        match other.kind {
            PErrorKind::Nom(e) => PError {
                context: input.clone(),
                kind: PErrorKind::Nom(VerboseError::append(input, kind, e)),
            },
            _ => other,
        }
    }

    /// creates an error from an input position and an expected character
    fn from_char(input: I, c: char) -> Self {
        PError {
            context: input.clone(),
            kind: PErrorKind::Nom(VerboseError::from_char(input, c)),
        }
    }

    /// combines two existing error. This function is used to compare errors
    /// generated in various branches of [alt]
    fn or(self, other: Self) -> Self {
        match self.kind {
            PErrorKind::Nom(_) => other,
            _ => self,
        }
    }

    /// create a new error from an input position, a static string and an existing error.
    /// This is used mainly in the [context] combinator, to add user friendly information
    /// to errors when backtracking through a parse tree
    fn add_context(input: I, ctx: &'static str, mut other: Self) -> Self {
        if let PErrorKind::Nom(e) = other.kind {
            other.kind = PErrorKind::Nom(VerboseError::add_context(input, ctx, e));
        }
        other
    }
}

/// Proper printing of errors.
impl<'src> fmt::Display for PError<PInput<'src>> {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        let message = match &self.kind {
            PErrorKind::Nom(e) => format!("Unprocessed parsing error: {:?}.\nPlease fill a BUG with context on when this happened!", e),
            #[cfg(test)]
            PErrorKind::NomTest(msg) => format!("Testing only error message, this should never happen {}.\nPlease fill a BUG with context on when this happened!", msg),
            PErrorKind::InvalidFormat => "Invalid header format, it must contain a single line '@format=x' where x is an integer. Shebang accepted.".to_string(),
            PErrorKind::InvalidName(i) => format!("The identifier is invalid in a {}.", i.fragment.bright_magenta()),
            PErrorKind::UnexpectedToken(s) => format!("Unexpected token, expecting '{}', found", s.bright_magenta()),
            PErrorKind::UnterminatedDelimiter(i) => format!("Missing closing delimiter for '{}'", i.fragment.bright_magenta()),
            PErrorKind::InvalidEnumExpression => "This enum expression is invalid".to_string(),
            PErrorKind::InvalidEscapeSequence => "This escape sequence cannot be used in a string".to_string(),
            PErrorKind::InvalidVariableReference => "This variable reference is invalid".to_string(),
            PErrorKind::ExpectedKeyword(s) => format!("Token not found, expected '{}'", s.bright_magenta()),
            PErrorKind::UnsupportedMetadata(i) => format!("Parsed comment or metadata not supported at this place: '{}' fount at {}", i.fragment.bright_magenta(), Token::from(*i).position_str().bright_yellow()),
        };
        f.write_str(&format!(
            "{}, near\n{}{} {}",
            Token::from(self.context).position_str().bright_yellow(),
            self.context.fragment,
            "-->".bright_blue(),
            message.bold(),
            
        ))
    }
}

/// Convert into a project error
impl Into<Error> for PError<PInput<'_>> {
    fn into(self) -> Error {
        Error::User(format!("{}", self))
    }
}

/// Combinator to force the error output to be a specific PError.
/// Having a combinator instead of a macro is much better since it can be used from within other
/// combinators.
// Here closures could use ParseError<I> but this way we force the type inference for
// all sub parsers to return PError<I>, which we won't have to specify during call.
// Pass a closure instead of a PErrorKind to allow lazy evaluation of its parameters
pub fn or_fail<'src, O, F, E>(f: F, e: E) -> impl Fn(PInput<'src>) -> PResult<'src, O>
where
    F: Fn(PInput<'src>) -> PResult<'src, O>,
    E: Fn() -> PErrorKind<PInput<'src>>,
{
    move |input| {
        let x = f(input);
        match x {
            // a non nom error cannot be superseded
            // keep original context when possible
            Err(Err::Failure(err)) => match err.kind {
                PErrorKind::Nom(_) => Err(Err::Failure(PError {
                    context: err.context,
                    kind: e(),
                })),
                _ => Err(Err::Failure(err)),
            },
            Err(Err::Error(err)) => Err(Err::Failure(PError {
                context: err.context,
                kind: e(),
            })),
            Err(Err::Incomplete(_)) => Err(Err::Failure(PError {
                context: input,
                kind: e(),
            })),
            Ok(y) => Ok(y),
        }
    }
}

/// Extract error from a parsing result and transforms it to the project's global error type.
pub fn fix_error_type<T>(res: PResult<T>) -> Result<T> {
    match res {
        Ok((_, t)) => Ok(t),
        Err(Err::Failure(e)) => Err(e.into()),
        Err(Err::Error(e)) => Err(e.into()),
        Err(Err::Incomplete(_)) => panic!("Incomplete should never happen"),
    }
}
