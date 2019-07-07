mod token;

use nom::branch::*;
use nom::bytes::complete::*;
use nom::character::complete::*;
use nom::combinator::*;
use nom::error::*;
use nom::multi::*;
use nom::sequence::*;
use nom::*;
use nom_locate::LocatedSpanEx;
use token::*;

//// Error.rs

/// This is the only error type that should be returned by the main parser.
/// It is an error that is suitable to give to the user as opposed to nom error that are suitable
/// for the developer.
/// Sub parsers may mix error types from nom or from finalerror
/// So this is a generir error type that must implement ParseError
#[derive(Debug, PartialEq)]
pub enum PError<I> {
    Nom(VerboseError<I>),
    InvalidFormat,
    UnterminatedString,
    InvalidEscape,
    UnterminatedDelimiter,
    InvalidName,
    EnumExpression,
    InvalidSeparator,
    InvalidVariableDefinition,
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

//// Error.rs

pub type Result<'src, O> = IResult<PInput<'src>, O, PError<PInput<'src>>>;

//// parser.rs
fn strip_spaces_and_comment(i: PInput) -> Result<()> {
    let (i, _) = many0(alt((
        // spaces
        multispace1,
        // simple comments (ie # but not ##)
        // value is here to have the same type as above
        value(
            pinput("", ""),
            delimited(
                tag("#"),
                opt(preceded(not(tag("#")), take_until("\n"))),
                newline,
            ),
        ),
    )))(i)?;
    Ok((i, ()))
}

// Equivalent of ws! but works for chars (instead of u8) and comments
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
fn header(i: PInput) -> Result<PHeader> {
    let (i, _) = opt(tuple((tag("#!/"), take_until("\n"), newline)))(i)?;
    let (i, _) = or_fail!(tag("@format=")(i), PError::InvalidFormat);
    let (i, version) = or_fail!(
        map_res(take_until("\n"), |s: PInput| s.fragment.parse::<u32>())(i),
        PError::InvalidFormat
    );
    let (i, _) = tag("\n")(i)?;
    Ok((i, PHeader { version }))
}

//// parser.rs
//// tests.rs

#[cfg(test)]
mod tests {
    use super::*;

    //    type Result<'src, O> = std::Result< (PInput<'src>,O), Err<PError<PInput<'src>>> >;

    // Adapter to simplify running test
    fn mapres<'src, F, O>(
        f: F,
        i: &'src str,
    ) -> std::result::Result<(&'src str, O), PError<PInput<'src>>>
    where
        F: Fn(PInput<'src>) -> Result<'src, O>,
    {
        match f(pinput(i, "")) {
            Ok((x, y)) => Ok((x.fragment, y)),
            Err(Err::Failure(e)) => Err(e),
            Err(Err::Error(e)) => Err(e),
            Err(Err::Incomplete(_)) => panic!("Incomplete should never happen"),
        }
    }

    #[test]
    fn test_spaces_and_comment() {
        assert_eq!(mapres(strip_spaces_and_comment, ""), Ok(("", ())));
        assert_eq!(mapres(strip_spaces_and_comment, "  \t\n"), Ok(("", ())));
        assert_eq!(
            mapres(strip_spaces_and_comment, "  \nhello "),
            Ok(("hello ", ()))
        );
        assert_eq!(
            mapres(
                strip_spaces_and_comment,
                "  \n#comment1 \n # comment2\n\n#comment3\n youpi"
            ),
            Ok(("youpi", ()))
        );
    }

    #[test]
    fn test_headers() {
        assert_eq!(
            mapres(header, "@format=21\n"),
            Ok(("", PHeader { version: 21 }))
        );
        assert_eq!(
            mapres(header, "#!/bin/bash\n@format=1\n"),
            Ok(("", PHeader { version: 1 }))
        );
        assert_eq!(mapres(header, "@format=21.5\n"), Err(PError::InvalidFormat));
    }

}

//// tests.rs

/////////////////////////////////////////

fn main() {
    //let code=pinput("file", "you_pi tralala # @name variable");
    //    let code=PInput::new_extra("you_pi tralala # @name variable", "file");
    //    let (id,(o1,o2)) = sp!(pair(pidentifier,pidentifier))(code).unwrap();
    //    println!("Parsed: '{}', '{}', '{}'",id.fragment,o1.fragment(),o2.fragment());
    let h = pinput("@format=x\nyoupi", "file");
    let (i, o) = header(h).unwrap();
    println!("Header version={:?}, rest={:?}", o, i);
    //    let (id,o) = pidentifier(code).unwrap();
    //    println!("Parsed: '{}', '{}'",id.fragment,o.fragment);
}
