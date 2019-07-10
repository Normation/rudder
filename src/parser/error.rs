use crate::error;
use super::{Result,PInput,Token};
use nom::error::{ParseError,ErrorKind,VerboseError};
use nom::{IResult, Err};
use std::fmt;

/// This is the only error type that should be returned by the main parser.
/// It is an error that is suitable to give to the user as opposed to nom error that are suitable
/// for the developer.
/// Sub parsers may mix error types from nom or from finalerror
/// So this is a generir error type that must implement ParseError
#[derive(Debug, PartialEq,Clone)]
pub enum PErrorKind<I> {
    Nom(VerboseError<I>),
    NomTest(String),               // should not be use outside of tests
    InvalidFormat,                 // in header
    InvalidName(I),                // in identifier expressions (type of expression)
    UnexpectedToken(&'static str), // anywhere (expected token)
    UnterminatedDelimiter(I),      // after an opening delimiter (first delimiter)
    InvalidEnumExpression,         // in enum expression
    InvalidEscapeSequence,         // in string definition
    InvalidVariableReference,      // during string interpolation
    ExpectedKeyword(&'static str), // anywhere (keyword type)
}

#[derive(Debug, PartialEq,Clone)]
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
            PErrorKind::Nom(e) => 
                PError {
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
        // TODO proper PInput formating with position
        let message = match &self.kind {
            PErrorKind::Nom(e) => format!("Unprocessed parsing error: {:?}.\nPlease fill a BUG with context on when this happened!", e),
            PErrorKind::NomTest(msg) => format!("Testing only error message, this should never happen {}.\nPlease fill a BUG with context on when this happened!", msg),
            PErrorKind::InvalidFormat => "Invalid header format, it must contain a single line '@format=x' where x is an integer. Shebang accepted.".to_string(),
            PErrorKind::InvalidName(i) => format!("The identifier is invalid in a {}.",i.fragment),
            PErrorKind::UnexpectedToken(s) => format!("Unexpected token, expecting '{}' instead", s),
            PErrorKind::UnterminatedDelimiter(i) => format!("Missing end of {}", i.fragment),
            PErrorKind::InvalidEnumExpression => "This enum expression is invalid".to_string(),
            PErrorKind::InvalidEscapeSequence => "This escape cannot be used in a string".to_string(),
            PErrorKind::InvalidVariableReference => "This variable reference is invalid".to_string(),
            PErrorKind::ExpectedKeyword(s) => format!("Token not found, expecting a '{}'",s),
        };
        f.write_str(&format!("{} near '{}' at {} in {}",
                             message,
                             self.context.fragment,
                             Token::from(self.context).position_str(),
                             self.context.extra))
    }
}

/// Combinator to force the error output to be a specific PError.
/// Having a combinator instead of a macro is much better since it can be used from within other
/// combinators.
// Here input function result could be ParseError<I> but this way we force the type inference for 
// all sub parsers to return PError<I>, which we won't have to specify during call.
pub fn or_fail<I, O, F>(f: F, e: PErrorKind<I>) 
    -> impl Fn(I) -> IResult<I, O, PError<I>>
    where 
        I: Clone,
        F: Fn(I) -> IResult<I, O, PError<I>>,
{
    move |input: I| {
        let x = f(input.clone());
        match x {
            // a non nom error cannot be superseded
            // keep original context when possible
            Err(Err::Failure(err)) => match err.kind {
                PErrorKind::Nom(_) => Err(Err::Failure(PError { context: err.context, kind: e.clone()})),
                _ => Err(Err::Failure(err)),
            },
            Err(Err::Error(err)) => Err(Err::Failure(PError { context: err.context, kind: e.clone()})),
            Err(Err::Incomplete(_)) => Err(Err::Failure(PError { context: input, kind: e.clone()})),
            Ok(y) => Ok(y),
        }
    }
}

/// Transform an ErrorKind from nom into the project's global error type.
fn format_error<'src>(err: PError<PInput<'src>>) -> error::Error {
    let (file, line, col) = Token::from(err.context).position();
    error::Error::Parsing(format!("{}", err), file,line,col)
}

/// Extract error from a parsing result and transforms it to the project's global error type.
// type conversion can be hard to follow
pub fn fix_error_type<T>(res: Result<T>) -> error::Result<T> {
    match res {
        Ok((_, t)) => Ok(t),
        Err(Err::Failure(e)) => Err(format_error(e)),
        Err(Err::Error(e)) => Err(format_error(e)),
        Err(Err::Incomplete(_)) => panic!("Incomplete should never happen"),
    }
}
