mod error;
mod token;

use error::*;
use nom::branch::*;
use nom::bytes::complete::*;
use nom::character::complete::*;
use nom::combinator::*;
use nom::multi::*;
use nom::sequence::*;
use nom::*;

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
                delimited(not(tag("#")), take_until("\n"), newline),
                // comment is the last line
                preceded(not(tag("#")), rest),
            )),
        ),
    )))(i)?;
    Ok((i, ()))
}

/// Macro to automatically append strip_spaces_and_comment to a parser (a=append)
/// This one could be a combinator, but we keep it a macro for coherency with spi!
macro_rules! spa {
    ($i:expr ) => {
        terminated( $i, strip_spaces_and_comment )
    };
}
/// Macro to automatically call strip_spaces_and_comment for each parameter of a method call
/// This avoids having to call it manually each time (i=insert)
/// On almost all combinators, this is also does an spa!
macro_rules! spi {
    // version for combinator call
    ($i:ident ( $($arg1:expr),* ) ) => {
            $i ( $(terminated( $arg1, strip_spaces_and_comment ),)* )
    };
}

/// A source file header consists of a single line '@format=<version>'.
/// Shebang accepted.
#[derive(Debug, PartialEq)]
pub struct PHeader {
    pub version: u32,
}
fn pheader(i: PInput) -> Result<PHeader> {
    let (i, _) = opt(tuple((tag("#!/"), take_until("\n"), newline)))(i)?;
    let (i, _) = or_fail(tag("@format="), PErrorKind::InvalidFormat)(i)?;
    let (i, version) = or_fail(
        map_res(take_until("\n"), |s: PInput| s.fragment.parse::<u32>()),
        PErrorKind::InvalidFormat
    )(i)?;
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
    let (i, lines) = many1(map(
        preceded(
            tag("##"),
            alt((
                terminated(take_until("\n"), newline),
                // comment is the last line
                rest,
            )),
        ),
        |x: PInput| x.into(),
    ))(i)?;
    Ok((i, PComment { lines }))
}

/// An identifier is a word that contains alphanumeric chars.
/// Be liberal here, they are checked again later
fn pidentifier(i: PInput) -> Result<Token> {
    map(
        take_while1(|c: char| c.is_alphanumeric() || (c == '_')),
        |x: PInput| x.into(),
    )(i)
}

/// An enum is a list of values, like a C enum.
/// An enum can be global, which means its values are globally unique and can be guessed without specifying type.
/// A global enum also has a matching global variable with the same name as its type.
#[derive(Debug, PartialEq)]
pub struct PEnum<'src> {
    pub global: bool,
    pub name: Token<'src>,
    pub items: Vec<Token<'src>>,
}
fn penum(i: PInput) -> Result<PEnum> {
    let (i, global) = spa!(opt(tag("global")))(i)?;
    let (i, e) = spa!(tag("enum"))(i)?;
    let (i, name) = spa!(or_fail(pidentifier, PErrorKind::InvalidName(e)))(i)?;
    let (i, b) = spa!(or_fail(tag("{"), PErrorKind::UnexpectedToken("{")))(i)?;
    let (i, items) = spi!(separated_nonempty_list(tag(","), pidentifier))(i)?;
    let (i, _) = spa!(opt(tag(",")))(i)?;
    let (i, _) = spa!(or_fail(tag("}"), PErrorKind::UnterminatedDelimiter(b)))(i)?;
    Ok((
        i,
        PEnum {
            global: global.is_some(),
            name,
            items,
        },
    ))
}

/// An enum mapping maps an enum to another one creating the second one in the process.
/// The mapping must map all items from the source enum.
/// A default keyword '*' can be used to map all unmapped items.
/// '*->xxx' maps then to xxx, '*->*' maps them to the same name in the new enum.
/// The new enum as the same properties as the original one .
#[derive(Debug, PartialEq)]
pub struct PEnumMapping<'src> {
    pub from: Token<'src>,
    pub to: Token<'src>,
    pub mapping: Vec<(Token<'src>, Token<'src>)>,
}
fn penum_mapping(i: PInput) -> Result<PEnumMapping> {
    let (i, e) = tag("enum")(i)?;
    let (i, _) = strip_spaces_and_comment(i)?;
    let (i,from) = or_fail(pidentifier,PErrorKind::InvalidName(e))(i)?;
    let (i, _) = strip_spaces_and_comment(i)?;
    let (i, _) = or_fail(tag("~>"),PErrorKind::UnexpectedToken("~>"))(i)?;
    let (i, _) = strip_spaces_and_comment(i)?;
    let (i,to) = or_fail(pidentifier,PErrorKind::InvalidName(e))(i)?;
    let (i, _) = strip_spaces_and_comment(i)?;
    let (i, b) = or_fail(tag("{"),PErrorKind::UnexpectedToken("{"))(i)?;
    let (i, _) = strip_spaces_and_comment(i)?;
    let (i, mapping) = 
        spi!(separated_nonempty_list(
            tag(","),
            separated_pair(
                or_fail(
                    alt((
                        pidentifier,
                        map(tag("*"),|x: PInput| x.into())
                    )),
                    PErrorKind::InvalidName(to.into())),
                or_fail(
                    tag("->"),
                    PErrorKind::UnexpectedToken("->")),
                or_fail(
                    alt((
                        pidentifier,
                        map(tag("*"),|x: PInput| x.into())
                    )),
                    PErrorKind::InvalidName(to.into()))
            )
        ))(i)?;
    let (i, _) = strip_spaces_and_comment(i)?;
    let (i, _) = opt(tag(","))(i)?;
    let (i, _) = strip_spaces_and_comment(i)?;
    let (i, _) = or_fail(tag("}"),PErrorKind::UnterminatedDelimiter(b))(i)?;
    Ok((i, PEnumMapping {from, to, mapping} ))
}
    
//pnamed!(pub penum_mapping<PEnumMapping>,
//    sp!(do_parse!(
//        tag!("enum") >>
//        from: or_fail!(pidentifier,PErrorKind::InvalidName) >>
//        or_fail!(tag!("~>"),PErrorKind::InvalidSeparator) >>
//        to: or_fail!(pidentifier,PErrorKind::InvalidName) >>
//        or_fail!(tag!("{"),PErrorKind::InvalidSeparator) >>
//        mapping: sp!(separated_list!(
//                tag!(","),
//                separated_pair!(
//                    or_fail!(alt!(pidentifier|map!(tag!("*"),|x| x.into())),PErrorKind::InvalidName),
//                    or_fail!(tag!("->"),PErrorKind::InvalidSeparator),
//                    or_fail!(alt!(pidentifier|map!(tag!("*"),|x| x.into())),PErrorKind::InvalidName))
//            )) >>
//        opt!(tag!(",")) >>
//        or_fail!(tag!("}"),PErrorKind::UnterminatedDelimiter) >>
//        (PEnumMapping {from, to, mapping})
//    ))
//);


// tests must be at the end to be able to test macros
#[cfg(test)]
mod tests;
