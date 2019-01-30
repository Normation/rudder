use super::{PFile, PInput, Token};
use crate::error;
use enum_primitive::*;
use nom::*;
use std::fmt;

/// PError is the error type for parser.
/// It is a special type that can be simply converted to and from u32.
/// This is because it is easier to use with existing errors in nom.
/// This would be useless if we had ErrorKind(PError) return codes but this
/// would mean writing a lot of fix_error ! calls in parsers.
// enum_from primitive allows recreating PError from u32 easily (ie without writing tons of
// boilerplate)
enum_from_primitive! {
#[derive(Debug, PartialEq)]
pub enum PError {
    // TODO check if it is possible to add parameters
    Unknown, // Should be used by tests only
    InvalidFormat,
    UnterminatedString,
    InvalidEscape,
    UnterminatedCurly,
    InvalidName,
    EnumExpression,
} }

/// Proper printing of errors.
impl fmt::Display for PError {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        f.write_str(match self {
            PError::Unknown => "Unknown error, this should not happen except in tests",
            PError::InvalidFormat => "Invalid format",
            PError::UnterminatedString => "Unterminated string",
            PError::InvalidEscape => "Invalide escape character after \\ in string",
            PError::UnterminatedCurly => "Unterminated curly brace",
            PError::InvalidName => "Invalid identifier name",
            PError::EnumExpression => "Invalid enum expression",
        })
    }
}

/// Transform an ErrorKind from nom into the project's global error type.
fn format_error(context: &Context<PInput, u32>) -> error::Error {
    match context {
        Context::Code(i, e) => {
            let (file, line, col) = Token::from(*i).position();
            match e {
                ErrorKind::Custom(err) => error::Error::Parsing(format!("Error: {} at {}:{}:{}",PError::from_u32(*err).unwrap(),file,line,col),file,line,col),
                e => error::Error::Parsing(format!("Unprocessed parsing error '{:?}' {:?} at {}:{}:{}, please fill a BUG with context on when this happened",e,i, file,line,col), file,line,col),
            }
        }
    }
}

/// Extract error from a parsing result and transforms it to the project's global error type.
// type conversion can be hard to follow
pub fn error_type<'a>(res: IResult<PInput<'a>, PFile<'a>, u32>) -> error::Result<PFile<'a>> {
    match res {
        Ok((_, file)) => Ok(file),
        Err(Err::Failure(context)) => Err(format_error(&context)),
        Err(Err::Error(context)) => Err(format_error(&context)),
        Err(Err::Incomplete(_)) => panic!("Incomplete should never happen"),
    }
}
