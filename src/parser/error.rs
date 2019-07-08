use nom::error::{ParseError,ErrorKind,VerboseError};
use nom::{IResult, Err};

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
    UnexpectedExpressionData,      // in enum expression
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
        match other.kind {
            PErrorKind::Nom(e) => other.kind = PErrorKind::Nom(VerboseError::add_context(input, ctx, e)),
            _ => {}
        }
        other
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
            Err(_) => Err(Err::Failure(PError { context: input, kind: e.clone()})),
            Ok(x) => Ok(x),
        }
    }
}
