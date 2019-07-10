mod error;
mod token;

use nom::branch::*;
use nom::bytes::complete::*;
use nom::character::complete::*;
use nom::combinator::*;
use nom::multi::*;
use nom::sequence::*;
use nom::IResult;

use std::collections::HashMap;

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
    move |i| {
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
///
/// We don't use a list or a tuple for sequence parsing because we want to 
/// use some intermediary result at some steps (for example for error management).
macro_rules! sequence {
    ( { $($f:ident : $parser:expr;)* } => $output:expr ) => {
        move |i| {
            $(
                let (i,$f) = $parser (i)?;
            )*
            Ok((i, $output))
        }
    };
}
/// wsequence is the same a sequence, but we automatically insert space parsing between each call
macro_rules! wsequence {
    ( { $($f:ident : $parser:expr;)* } => $output:expr ) => {
        move |i| {
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
    sequence!(
        {
            _x: opt(tuple((tag("#!/"), take_until("\n"), newline)));
            _x: or_fail(tag("@format="), PErrorKind::InvalidFormat);
            version: or_fail(
                map_res(take_until("\n"), |s: PInput| s.fragment.parse::<u32>()),
                PErrorKind::InvalidFormat
                );
            _x: tag("\n");
        } => PHeader { version }
    )(i)
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
    wsequence!(
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
    wsequence!(
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

pub fn penum_expression_full(i: PInput) -> Result<PEnumExpression> {
    // an expression must consume the full string as this parser can be used in isolation
    or_fail(
        all_consuming(terminated(penum_expression,strip_spaces_and_comment)),
        PErrorKind::InvalidEnumExpression
    )(i)
}
fn penum_expression(i: PInput) -> Result<PEnumExpression> {
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
        wsequence!(
            {
                t: tag("(");
                e: penum_expression;
                _x: or_fail(tag(")"), PErrorKind::UnterminatedDelimiter(t));
            } => e
        ),
        wsequence!(
            {
                var: pidentifier;
                _x: tag("=~");
                penum: opt(terminated(pidentifier, sp(tag(":"))));
                value: or_fail(pidentifier, PErrorKind::InvalidEnumExpression);
            } => PEnumExpression::Compare(Some(var), penum, value)
        ),
        wsequence!(
            {
                var: pidentifier;
                _x: tag("!~");
                penum: opt(terminated(pidentifier, sp(tag(":"))));
                value: or_fail(pidentifier, PErrorKind::InvalidEnumExpression);
            } => PEnumExpression::Not(Box::new(PEnumExpression::Compare(Some(var), penum, value)))
        ),
        wsequence!(
            {
                penum: opt(terminated(pidentifier, sp(tag(":"))));
                value: pidentifier;
            } => PEnumExpression::Compare(None, penum, value)
        )
    ))(i)
}
fn enum_or_expression(i: PInput) -> Result<PEnumExpression> {
    wsequence!(
        {
            left: alt((enum_and_expression, enum_not_expression, enum_atom));
            _x: tag("||");
            right: or_fail(
                       alt((enum_or_expression, enum_and_expression, enum_not_expression, enum_atom)),
                       PErrorKind::InvalidEnumExpression);
        } => PEnumExpression::Or(Box::new(left), Box::new(right))
    )(i)
}
fn enum_and_expression(i: PInput) -> Result<PEnumExpression> {
    wsequence!(
        {
            left: alt((enum_not_expression, enum_atom));
            _x: tag("&&");
            right: or_fail(
                       alt((enum_and_expression, enum_not_expression, enum_atom)),
                       PErrorKind::InvalidEnumExpression);
        } => PEnumExpression::And(Box::new(left), Box::new(right))
    )(i)
}
fn enum_not_expression(i: PInput) -> Result<PEnumExpression> {
    wsequence!(
        {
            _x: tag("!");
            value: or_fail(enum_atom, PErrorKind::InvalidEnumExpression);
        } => PEnumExpression::Not(Box::new(value))
    )(i)
}

/// An escaped string is a string delimited by '"' and that support backslash escapes.
/// The token is here to keep position
fn pescaped_string(i: PInput) -> Result<(Token, String)> {
    // Add type annotation to help the type solver
    let f: fn(PInput) -> Result<(Token, String)> = sequence!(
        {
            prefix: tag("\"");
            content: alt((
                        // empty lines are not properly handled by escaped_transform
                        // so we detect them here beforehand
                        peek(value("".into(), tag("\""))),
                        or_fail(
                            escaped_transform(
                                take_till1(|c: char| (c == '\\')||(c == '"')),
                                '\\',
                                alt((
                                   value("\\", tag("\\")),
                                   value("\"", tag("\"")),
                                   value("\n", tag("n")),
                                   value("\r", tag("r")),
                                   value("\t", tag("t")),
                                ))
                            ),
                            PErrorKind::InvalidEscapeSequence
                        )
                    ));
            _x: or_fail(tag("\""), PErrorKind::UnterminatedDelimiter(prefix));
        } => (prefix.into(), content)
    );
    f(i)
}

/// An unescaped string is a literal string delimited by '"""'.
/// The token is here to keep position
fn punescaped_string(i: PInput) -> Result<(Token, String)> {
    sequence!(
        {
            prefix: tag("\"\"\"");
            content: map(
                         or_fail(take_until("\"\"\""), PErrorKind::UnterminatedDelimiter(prefix)),
                         |x: PInput| x.to_string()
                    );
            _x: or_fail(tag("\"\"\""), PErrorKind::UnterminatedDelimiter(prefix));
        } => (prefix.into(), content)
    )(i)
}

/// All strings should be interpolated
#[derive(Debug, PartialEq, Clone)]
pub enum PInterpolatedElement {
    Static(String),   // static content
    Variable(String), // variable name
}
fn pinterpolated_string(i: PInput) -> Result<Vec<PInterpolatedElement>> {
    // There is a rest inside so this just serve as a guard
    all_consuming(
        many1(alt((
            value(PInterpolatedElement::Static("$".into()), tag("$$")),
            sequence!(
                {
                    s: tag("${");
                    variable: or_fail(pidentifier, PErrorKind::InvalidVariableReference);
                    _x: or_fail(tag("}"), PErrorKind::UnterminatedDelimiter(s));
                } => PInterpolatedElement::Variable(variable.fragment().into())
            ),
            sequence!(
                {
                    _s: tag("$"); // $SomethingElse is an error
                    _x: or_fail(tag("$"), PErrorKind::InvalidVariableReference); // $$ is already processed so this is an error
                } => PInterpolatedElement::Static("".into()) // this is mandatory but cannot happen
            ),
            map(take_until("$"), |s: PInput| PInterpolatedElement::Static(s.fragment.into())),
            map(preceded(
                    peek(anychar), // do no take rest if we are already at the end
                    rest),
                |s: PInput| PInterpolatedElement::Static(s.fragment.into())),
        )))
   )(i)
}

/// A PType is the type a variable or a parameter can take.
#[derive(Debug, PartialEq, Clone, Copy)]
pub enum PType {
    String,
    Integer,
    Boolean,
    Struct,
    List,
}
fn ptype(i: PInput) -> Result<PType> {
    alt((
        value(PType::String,  tag("string")),
        value(PType::Integer, tag("int")),
        value(PType::Boolean, tag("boolean")),
        value(PType::Struct,  tag("struct")),
        value(PType::List,    tag("list")),
    ))(i)
}

/// PValue is a typed value of the content of a variable or a parameter.
/// Must be cloneable because it is copied during default values expansion
#[derive(Debug, PartialEq)]
pub enum PValue<'src> {
    String(Token<'src>, String),
    Integer(Token<'src>, i64),
    EnumExpression(PEnumExpression<'src>),
    List(Vec<PValue<'src>>),
    Struct(HashMap<Token<'src>,PValue<'src>>),
}
impl<'src> PValue<'src> {
    pub fn get_type(&self) -> PType {
        match self {
            PValue::String(_,_)       => PType::String,
            PValue::Integer(_,_)      => PType::Integer,
            PValue::EnumExpression(_) => PType::Boolean,
            PValue::Struct(_)         => PType::Struct,
            PValue::List(_)           => PType::List,
        }
    }
}
fn pvalue(i: PInput) -> Result<PValue> {
    alt((
        // Be careful of ordering here
        map(punescaped_string, |(x,y)| PValue::String(x,y)),
        map(pescaped_string,   |(x,y)| PValue::String(x,y)),
        map(penum_expression,  |x|     PValue::EnumExpression(x)),
    ))(i)
}

// tests must be at the end to be able to test macros
#[cfg(test)]
mod tests;
