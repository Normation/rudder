// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use super::{PInput, Token};
use crate::error::*;
use colored::Colorize;
use nom::{
    error::{ErrorKind, ParseError, VerboseError},
    Err, IResult,
};
use std::fmt;

/// Result for all parser
pub type PResult<'src, O> = IResult<PInput<'src>, O, PError<PInput<'src>>>;

/// This is the only error type that should be returned by the main parser.
/// It is an error that is suitable to give to the user as opposed to nom error that are suitable
/// for the developer.
/// Sub parsers may mix error types from nom or from finalerror
/// So this is a generir error type that must implement ParseError
#[derive(Debug, PartialEq, Clone)]
pub enum PErrorKind<I> {
    Nom(VerboseError<I>), // TODO remove this one
    #[cfg(test)]
    NomTest(String), // cannot be use outside of tests
    ExpectedKeyword(&'static str), // anywhere (keyword type)
    // ExpectedReservedWord(&'static str), // anywhere
    ExpectedToken(&'static str), // anywhere (expected token)
    InvalidEnumExpression,       // in enum expression
    InvalidEscapeSequence,       // in string definition
    InvalidFormat,               // in header
    InvalidName(I),              // in identifier expressions (type of expression)
    InvalidVariableReference,    // during string interpolation
    UnsupportedMetadata(I),      // metadata or comments are not supported everywhere (metadata key)
    UnterminatedDelimiter(I),    // after an opening delimiter (first delimiter)
    Unparsed(I),                 // cannot be parsed
}

#[derive(Debug, PartialEq, Clone)]
pub struct PError<I> {
    pub context: Option<I>,
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
            context: None,
            kind: PErrorKind::Nom(VerboseError::from_error_kind(input, kind)),
        }
    }

    /// combines an existing error with a new one created from the input
    /// position and an [ErrorKind]. This is useful when backtracking
    /// through a parse tree, accumulating error context on the way
    fn append(input: I, kind: ErrorKind, other: Self) -> Self {
        match other.kind {
            PErrorKind::Nom(e) => PError {
                context: None,
                kind: PErrorKind::Nom(VerboseError::append(input, kind, e)),
            },
            _ => other,
        }
    }

    /// creates an error from an input position and an expected character
    fn from_char(input: I, c: char) -> Self {
        PError {
            context: None,
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
            PErrorKind::Nom(e) => format!("Unprocessed parsing error: '{:#?}'.\nPlease fill a BUG with context on when this happened!", e),
            #[cfg(test)]
            PErrorKind::NomTest(msg) => format!("Testing only error message, this should never happen {}.\nPlease fill a BUG with context on when this happened!", msg),
            PErrorKind::ExpectedKeyword(s) => format!("The following keyword was expected: '{}'.", s.bright_magenta()),
            // PErrorKind::ExpectedReservedWord(s) => format!("The following reserved keyword was expected: '{}'.", s.bright_magenta()),
            PErrorKind::ExpectedToken(s) => format!("The following token was expected '{}'.", s.bright_magenta()),
            PErrorKind::InvalidEnumExpression => "This enum expression is invalid".to_string(),
            PErrorKind::InvalidEscapeSequence => "This escape sequence cannot be used in a string".to_string(),
            PErrorKind::InvalidFormat => "Invalid header format, it must contain a single line '@format=x' where x is an integer. Shebang accepted.".to_string(),
            PErrorKind::InvalidName(i) => format!("The identifier is invalid in a {}.", i.fragment.bright_magenta()),
            PErrorKind::InvalidVariableReference => "This variable reference is invalid".to_string(),
            PErrorKind::UnsupportedMetadata(i) => format!("Parsed comment or metadata not supported at this place: '{}' found at {}", i.fragment.bright_magenta(), Token::from(*i).position_str().bright_yellow()),
            PErrorKind::UnterminatedDelimiter(i) => format!("Missing closing delimiter for '{}'", i.fragment.bright_magenta()),
            PErrorKind::Unparsed(i) => format!("Could not parse the following: '{}'", i.fragment.bright_magenta()),
        };

        // simply removes superfluous line return (prettyfication)
        match self.context {
            Some(ctx) => {
                let context = ctx.fragment.trim_end_matches('\n');
                // Formats final error output
                f.write_str(&format!(
                    "{} near '{}'\n{} {}",
                    Token::from(ctx).position_str().bright_yellow(),
                    context,
                    "-->".bright_blue(),
                    message.bold(),
                ))
            }
            None => f.write_str(&format!(
                "{}\n{} {}",
                "undefined context".bright_yellow(),
                "-->".bright_blue(),
                message.bold(),
            )),
        }
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
        match f(input) {
            // a non nom error cannot be superseded
            // keep original context when possible
            Err(Err::Failure(err)) => match err.kind {
                PErrorKind::Nom(_) => Err(Err::Failure(PError {
                    context: None,
                    kind: e(),
                })),
                _ => Err(Err::Failure(err)),
            },
            Err(Err::Error(_)) => Err(Err::Failure(PError {
                context: None,
                kind: e(),
            })),
            Err(Err::Incomplete(_)) => panic!("Incomplete should never happen"),
            Ok(y) => Ok(y),
        }
    }
}

/// Similar code to `or_fail()` yet usage and behavior differ:
/// primarly it is non-terminating as it does not turn Errors into Failures
/// but rather gives it a PError context (E parameter) therefore updating the generic Nom::ErrorKind
pub fn or_err<'src, O, F, E>(f: F, e: E) -> impl Fn(PInput<'src>) -> PResult<'src, O>
where
    F: Fn(PInput<'src>) -> PResult<'src, O>,
    E: Fn() -> PErrorKind<PInput<'src>>,
{
    move |input| match f(input) {
        Err(Err::Failure(err)) => Err(Err::Failure(err)),
        Err(Err::Error(_)) => Err(Err::Error(PError {
            context: None,
            kind: e(),
        })),
        Err(Err::Incomplete(_)) => panic!("Incomplete should never happen"),
        Ok(y) => Ok(y),
    }
}

/// This function turns our own `Error`s (not nom ones) into `Failure`s so they are properly handled
/// by the `nom::multi::many0()` function which abstracts Errors, only breaking on failures which was an issue
pub fn or_fail_perr<'src, O, F>(f: F) -> impl Fn(PInput<'src>) -> PResult<'src, O>
where
    F: Fn(PInput<'src>) -> PResult<'src, O>,
{
    move |input| match f(input) {
        Err(Err::Failure(e)) => Err(Err::Failure(e)),
        Err(Err::Error(e)) => match &e.kind {
            PErrorKind::Nom(_) => Err(Err::Error(PError {
                context: None,
                kind: e.kind,
            })),
            _ => Err(Err::Failure(e)),
        },
        Err(Err::Incomplete(_)) => panic!("Incomplete should never happen"),
        Ok(y) => Ok(y),
    }
}

/// Updates content of an error to fit and capture a better context
/// Solely exists for (w)sequence macro
pub fn update_error_context<'src>(
    e: Err<PError<PInput<'src>>>,
    new_ctx: PInput<'src>,
) -> Err<PError<PInput<'src>>> {
    match e {
        Err::Failure(err) => {
            let context = match err.context {
                None => Some(new_ctx),
                Some(context_to_keep) => Some(context_to_keep),
            };
            Err::Failure(PError {
                context,
                kind: err.kind,
            })
        }
        Err::Error(err) => {
            let context = match err.context {
                None => Some(new_ctx),
                Some(context_to_keep) => Some(context_to_keep),
            };
            Err::Error(PError {
                context,
                kind: err.kind,
            })
        }
        Err::Incomplete(_) => panic!("Incomplete should never happen"),
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
