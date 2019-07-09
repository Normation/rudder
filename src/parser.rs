mod error;
mod token;

use nom::branch::*;
use nom::bytes::complete::*;
use nom::character::complete::*;
use nom::combinator::*;
use nom::multi::*;
use nom::sequence::*;
use nom::IResult;

use error::{PError,PErrorKind,or_fail};
use token::PInput;
// reexport tokens
pub use token::Token;

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

/// Combinator automatically call strip_spaces_and_comment before and after a parser
/// This avoids having to call it manually many times
fn sp<'src, O, F>(f: F) -> impl Fn(PInput<'src>) -> Result<O> 
    where F: Fn(PInput<'src>) -> Result<O>,
          O: 'src,
{
    move |i: PInput| {
        let (i,_) = strip_spaces_and_comment(i)?;
        let (i,r) = f(i)?;
        let (i,_) = strip_spaces_and_comment(i)?;
        Ok((i,r))
    }
}

/// A bit like do_parse! 
///
/// Transforms:
///     { 
///         variable: combinator(parser);
///         ...
///     } => Object { variable, ... }
/// Into a series of sequential calls like this:
///     |i| 
///     let(i,variable) = combinator(parser)(i)?;
///     let (i,_) = strip_spaces_and_comment(i)?
///     ...
///     Ok((i,Object { variable, ... }))
///
/// The result is a closure parser that can be used in place of any other parser
/// As you can see, we automatically insert space parsing between each call
///
/// We don't use a list or a tuple for sequence parsing because we want to 
/// use some intermediary result at some steps (for example for error management).
macro_rules! sequence {
    ( { $($f:ident : $parser:expr;)* } => $output:expr ) => {
        |i| {
            $(
                let (i,$f) = $parser (i)?;
                let (i,_) = strip_spaces_and_comment(i)?;
            )*
            Ok((i, $output))
        }
    };
}

/// A source file header consists of a single line '@format=<version>'.
/// Shebang accepted.
#[derive(Debug, PartialEq)]
pub struct PHeader {
    pub version: u32,
}
fn pheader(i: PInput) -> Result<PHeader> {
    // cannot use sequence because we don't want space parsing
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
    sequence!(
        {
            global: opt(tag("global"));
            e:      tag("enum");
            name:   or_fail(pidentifier, PErrorKind::InvalidName(e));
            b:      or_fail(tag("{"), PErrorKind::UnexpectedToken("{"));
            items:  separated_nonempty_list(sp(tag(",")), pidentifier);
            _x:     opt(tag(","));
            _x:     or_fail(tag("}"), PErrorKind::UnterminatedDelimiter(b));
        } => PEnum {
                global: global.is_some(),
                name,
                items,
        }
    )(i)
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
    sequence!(
        {
            e:    tag("enum");
            from: or_fail(pidentifier,PErrorKind::InvalidName(e));
            _x:   or_fail(tag("~>"),PErrorKind::UnexpectedToken("~>"));
            to:   or_fail(pidentifier,PErrorKind::InvalidName(e));
            b:    or_fail(tag("{"),PErrorKind::UnexpectedToken("{"));
            mapping: 
                separated_nonempty_list(
                    sp(tag(",")),
                    separated_pair(
                        or_fail(
                            alt((
                                pidentifier,
                                map(tag("*"),|x: PInput| x.into())
                            )),
                            PErrorKind::InvalidName(to.into())),
                        or_fail(
                            sp(tag("->")),
                            PErrorKind::UnexpectedToken("->")),
                        or_fail(
                            alt((
                                pidentifier,
                                map(tag("*"),|x: PInput| x.into())
                            )),
                            PErrorKind::InvalidName(to.into()))
                    )
                );
            _x: opt(tag(","));
            _x: or_fail(tag("}"),PErrorKind::UnterminatedDelimiter(b));
        } => PEnumMapping {from, to, mapping}
    )(i)
}
    
/// An enum expression is used as a condition in a case expression.
/// This is a boolean expression based on enum comparison.
/// A comparison check if the variable is of the right type and contains
/// the provided item as a value, or an ancestor item if this is a mapped enum.
/// 'default' is a value that is equivalent of 'true'.
#[derive(Debug, PartialEq)]
pub enum PEnumExpression<'src> {
    //             variable                 enum              value/item
    Compare(Option<Token<'src>>, Option<Token<'src>>, Token<'src>),
    And(Box<PEnumExpression<'src>>, Box<PEnumExpression<'src>>),
    Or(Box<PEnumExpression<'src>>, Box<PEnumExpression<'src>>),
    Not(Box<PEnumExpression<'src>>),
    Default(Token<'src>),
}
impl<'src> PEnumExpression<'src> {
    // extract the first token of the expression
    pub fn token(&self) -> Token<'src> {
        match self {
            PEnumExpression::Compare(_,_,v) => *v,
            PEnumExpression::And(a,_) => a.token(),
            PEnumExpression::Or(a,_) => a.token(),
            PEnumExpression::Not(a) => a.token(),
            PEnumExpression::Default(t) => *t,
        }
    }
}

pub fn penum_expression(i: PInput) -> Result<PEnumExpression> {
    // an expression must consume the full string as this parser can be used in isolation
    or_fail(
        all_consuming(terminated(sub_enum_expression,strip_spaces_and_comment)),
        PErrorKind::InvalidEnumExpression
    )(i)
}
fn sub_enum_expression(i: PInput) -> Result<PEnumExpression> {
    alt((
        enum_or_expression,
        enum_and_expression,
        enum_not_expression,
        map(tag("default"), |t| PEnumExpression::Default(Token::from(t))), // default looks like an atom so it must come first
        enum_atom
    ))(i)
}
// TODO it is highly probable that we can add better errors here
fn enum_atom(i: PInput) -> Result<PEnumExpression> {
    alt((
        sequence!(
            {
                t: tag("(");
                e: sub_enum_expression;
                _x: or_fail(tag(")"), PErrorKind::UnterminatedDelimiter(t));
            } => e
        ),
        sequence!(
            {
                var: pidentifier;
                _x: tag("=~");
                penum: opt(terminated(pidentifier, sp(tag(":"))));
                value: pidentifier;
            } => PEnumExpression::Compare(Some(var), penum, value)
        ),
        sequence!(
            {
                var: pidentifier;
                _x: tag("!~");
                penum: opt(terminated(pidentifier, sp(tag(":"))));
                value: pidentifier;
            } => PEnumExpression::Not(Box::new(PEnumExpression::Compare(Some(var), penum, value)))
        ),
        sequence!(
            {
                penum: opt(terminated(pidentifier, sp(tag(":"))));
                value: pidentifier;
            } => PEnumExpression::Compare(None, penum, value)
        )
    ))(i)
}
fn enum_or_expression(i: PInput) -> Result<PEnumExpression> {
    sequence!(
        {
            left: alt((enum_and_expression, enum_not_expression, enum_atom));
            _x: tag("||");
            right: alt((enum_or_expression, enum_and_expression, enum_not_expression, enum_atom));
        } => PEnumExpression::Or(Box::new(left), Box::new(right))
    )(i)
}
fn enum_and_expression(i: PInput) -> Result<PEnumExpression> {
    sequence!(
        {
            left: alt((enum_not_expression, enum_atom));
            _x: tag("&&");
            right: alt((enum_and_expression, enum_not_expression, enum_atom));
        } => PEnumExpression::And(Box::new(left), Box::new(right))
    )(i)
}
fn enum_not_expression(i: PInput) -> Result<PEnumExpression> {
    sequence!(
        {
            _x: tag("!");
            value: enum_atom;
        } => PEnumExpression::Not(Box::new(value))
    )(i)
}

/// A PType is the type a variable or a parameter can take.
#[derive(Debug, PartialEq, Clone, Copy)]
pub enum PType {
    TString,
    TBoolean,
    TInteger,
    TStruct,
    TList,
}
fn ptype(i: PInput) -> Result<PType> {
    alt((
        value(PType::TString, tag("string")),
        value(PType::TInteger, tag("int")),
        value(PType::TStruct, tag("struct")),
        value(PType::TList, tag("struct")),
    ))(i)
}


// tests must be at the end to be able to test macros
#[cfg(test)]
mod tests;
