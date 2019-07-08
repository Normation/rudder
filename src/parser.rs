mod token;
mod error;

use nom::*;
use nom::branch::*;
use nom::bytes::complete::*;
use nom::character::complete::*;
use nom::combinator::*;
use nom::multi::*;
use nom::sequence::*;
use error::*;

// reexport tokens
pub use token::*;

/// Result for all parser
type Result<'src, O> = IResult<PInput<'src>, O, PError<PInput<'src>>>;

/// Eat everything that can be ignored between tokens 
/// ie white spaces, newlines and simple comments (with a single #)
fn strip_spaces_and_comment(i: PInput) -> Result<()> {
    let (i, _) = many0(alt((
        // spaces
        multispace1,
        // simple comments (ie # but not ##)
        terminated(
            tag("#"),
            alt((
                delimited(
                    not(tag("#")),
                    take_until("\n"),
                    newline
                ),
                // comment is the last line
                preceded(not(tag("#")),rest)
            ))
        ),
    )))(i)?;
    Ok((i, ()))
}

/// Macro to automatically call strip_spaces_and_comment for each parameter of a method call
/// This avoids having to call it manually each time
macro_rules! sp (
    ($i:ident ) => (
        {
            terminated( $i, strip_spaces_and_comment )
        }
    );
    ($i:ident ($arg1:expr) ) => (
        {
            $i ( terminated( $arg1, strip_spaces_and_comment ) )
        }
    );
    ($i:ident ($arg1:expr,$arg2:expr) ) => (
        {
            $i ( terminated($arg1,strip_spaces_and_comment),
                 terminated($arg2,strip_spaces_and_comment) )
        }
    );
    ($i:ident ($arg1:expr,$arg2:expr,$arg3:expr) ) => (
        {
            $i ( terminated($arg1,strip_spaces_and_comment),
                 terminated($arg2,strip_spaces_and_comment),
                 terminated($arg3,strip_spaces_and_comment) )
        }
    );
    ($i:ident ($arg1:expr,$arg2:expr,$arg3:expr,$arg4:expr) ) => (
        {
            $i ( terminated($arg1,strip_spaces_and_comment),
                 terminated($arg2,strip_spaces_and_comment),
                 terminated($arg3,strip_spaces_and_comment),
                 terminated($arg4,strip_spaces_and_comment) )
        }
    );
);

/// A source file header consists of a single line '@format=<version>'.
/// Shebang accepted.
#[derive(Debug, PartialEq)]
pub struct PHeader {
    pub version: u32,
}
fn pheader(i: PInput) -> Result<PHeader> {
    let (i, _) = opt(tuple((tag("#!/"), take_until("\n"), newline)))(i)?;
    let (i, _) = or_fail!(tag("@format=")(i), PError::InvalidFormat);
    let (i, version) = or_fail!(
        map_res(take_until("\n"), |s: PInput| s.fragment.parse::<u32>())(i),
        PError::InvalidFormat
    );
    let (i, _) = tag("\n")(i)?;
    Ok((i, PHeader { version }))
}

/// A parsed comment block starts with a ## and ends with the end of line.
/// Such comment is parsed and kept contrarily to comments starting with '#'.
#[derive(Debug, PartialEq)]
pub struct PComment<'src> {
    lines: Vec<Token<'src>>,
}
fn pcomment(i: PInput) -> Result<PComment> {
    let (i,lines) = many1(
        map(
            preceded(
                tag("##"),
                alt((
                    terminated(take_until("\n"),newline),
                    rest
                ))
            ),
            |x: PInput| x.into()
        )
    )(i)?;
    Ok((i, PComment { lines }))
}

/// An identifier is a word that contains alphanumeric chars.
/// Be liberal here, they are checked again later
fn pidentifier(i: PInput) -> Result<Token> {
    map(
        take_while1(|c: char| c.is_alphanumeric() || (c == '_')),
        |x: PInput| x.into()
    )(i)
}

// tests must be at the end to be able to test macros
#[cfg(test)]
mod tests;

