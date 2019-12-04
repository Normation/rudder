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

mod error;
mod token;

use nom::branch::*;
use nom::bytes::complete::*;
use nom::character::complete::*;
use nom::combinator::*;
use nom::multi::*;
use nom::number::complete::*;
use nom::sequence::*;

use std::collections::HashMap;

use crate::error::*;
use error::*;
// reexport tokens
pub use token::Token;
#[allow(unused_imports)]
use token::*;
// reexport PInput for tests
#[cfg(test)]
pub use token::PInput;

///! All structures are public to be read directly by other modules.
///! Parsing errors must be avoided if possible since they are fatal.
///! Keep the structure and handle the error in later analyser if possible.
///!
///! All parsers should manage whitespace inside them.
///! All parser assume whitespaces at the beginning of the input have been removed.

// TODO v2: measures, actions, functions, iterators, include, proptest
// ===== Public interfaces =====

/// PAST is just a global structure parsed data sequentially.
#[derive(Debug)]
pub struct PAST<'src> {
    pub enums: Vec<PEnum<'src>>,
    pub enum_mappings: Vec<PEnumMapping<'src>>,
    pub resources: Vec<PResourceDef<'src>>,
    pub states: Vec<PStateDef<'src>>,
    pub variable_declarations: Vec<(Token<'src>, PValue<'src>)>,
    pub parameter_defaults: Vec<(Token<'src>, Option<Token<'src>>, Vec<Option<PValue<'src>>>)>, // separate parameter defaults since they will be processed first
    pub parents: Vec<(Token<'src>, Token<'src>)>,
    pub aliases: Vec<PAliasDef<'src>>,
}

impl<'src> PAST<'src> {
    pub fn new() -> PAST<'static> {
        PAST {
            enums: Vec::new(),
            enum_mappings: Vec::new(),
            resources: Vec::new(),
            states: Vec::new(),
            variable_declarations: Vec::new(),
            parameter_defaults: Vec::new(),
            parents: Vec::new(),
            aliases: Vec::new(),
        }
    }

    /// The parse function that should be called to parse a file
    pub fn add_file(&mut self, filename: &'src str, content: &'src str) -> Result<()> {
        let pfile = fix_error_type(pfile(PInput::new_extra(content, filename)))?;
        if pfile.header.version != 0 {
            return Err(Error::User(format!(
                "Format not supported yet: {}",
                pfile.header.version
            )));
        }
        pfile
            .code
            .into_iter()
            .for_each(|declaration| match declaration {
                PDeclaration::Enum(e) => self.enums.push(e),
                PDeclaration::Mapping(e) => self.enum_mappings.push(e),
                PDeclaration::Resource((r, d, p)) => {
                    self.parameter_defaults.push((r.name, None, d));
                    if let Some(parent) = p {
                        self.parents.push((r.name, parent))
                    };
                    self.resources.push(r);
                }
                PDeclaration::State((s, d)) => {
                    self.parameter_defaults.push((
                        s.resource_name,
                        Some(s.name),
                        d,
                    ));
                    self.states.push(s);
                }
                PDeclaration::GlobalVar(kv) => self.variable_declarations.push(kv),
                PDeclaration::Alias(a) => self.aliases.push(a),
            });
        Ok(())
    }
}

/// Parse a string for interpolation
pub fn parse_string(content: &str) -> Result<Vec<PInterpolatedElement>> {
    fix_error_type(pinterpolated_string(PInput::new_extra(content, "")))
}

// ===== Parsers =====

// TODO nomplus: sp!(parser) sp!(parser,sp) sp sequence, wsequence, cut_with

/// Eat everything that can be ignored between tokens
/// ie white spaces, newlines and simple comments (with a single #)
fn strip_spaces_and_comment(i: PInput) -> PResult<()> {
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
fn sp<'src, O, F>(f: F) -> impl Fn(PInput<'src>) -> PResult<O>
where
    F: Fn(PInput<'src>) -> PResult<O>,
    O: 'src,
{
    move |i| {
        let (i, _) = strip_spaces_and_comment(i)?;
        let (i, r) = f(i)?;
        let (i, _) = strip_spaces_and_comment(i)?;
        Ok((i, r))
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
fn pheader(i: PInput) -> PResult<PHeader> {
    sequence!(
        {
            _x: opt(tuple((tag("#!/"), take_until("\n"), newline)));
            _x: or_fail(tag("@format="), || PErrorKind::InvalidFormat);
            version: or_fail(
                map_res(take_until("\n"), |s: PInput| s.fragment.parse::<u32>()),
                || PErrorKind::InvalidFormat
                );
            _x: tag("\n");
        } => PHeader { version }
    )(i)
}

/// An identifier is a word that contains alphanumeric chars.
/// Be liberal here, they are checked again later
fn pidentifier(i: PInput) -> PResult<Token> {
    map(
        take_while1(|c: char| c.is_alphanumeric() || (c == '_')),
        |x: PInput| x.into(),
    )(i)
}

/// A variable identifier is a list of dot separated identifiers
fn pvariable_identifier(i: PInput) -> PResult<Token> {
    map(
        take_while1(|c: char| c.is_alphanumeric() || (c == '_') || (c == '.')),
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
fn penum(i: PInput) -> PResult<PEnum> {
    wsequence!(
        {
            metadata: pmetadata_list; // metadata unsupported here, check done after 'enum' tag
            global: opt(tag("global")); // TODO at least one space here
            e:      tag("enum"); // TODO at least one space here
            _fail: or_fail(verify(peek(anychar), |_| metadata.is_empty()), || PErrorKind::UnsupportedMetadata(metadata[0].key.into()));
            name:   or_fail(pidentifier, || PErrorKind::InvalidName(e));
            b:      tag("{"); // do not fail here, it could still be a mapping
            items:  separated_nonempty_list(sp(tag(",")), pidentifier);
            _x:     opt(tag(","));
            _x:     or_fail(tag("}"), || PErrorKind::UnterminatedDelimiter(b));
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
fn penum_mapping(i: PInput) -> PResult<PEnumMapping> {
    wsequence!(
        {
            metadata: pmetadata_list; // metadata unsupported here, check done after 'enum' tag
            e:    tag("enum");// TODO at least one space here
            _fail: or_fail(verify(peek(anychar), |_| metadata.is_empty()), || PErrorKind::UnsupportedMetadata(metadata[0].key.into()));
            from: or_fail(pidentifier,|| PErrorKind::InvalidName(e));
            _x:   or_fail(tag("~>"),|| PErrorKind::UnexpectedToken("~>"));
            to:   or_fail(pidentifier,|| PErrorKind::InvalidName(e));
            b:    or_fail(tag("{"),|| PErrorKind::UnexpectedToken("{"));
            mapping:
                separated_nonempty_list(
                    sp(tag(",")),
                    separated_pair(
                        or_fail(
                            alt((
                                pidentifier,
                                map(tag("*"),|x: PInput| x.into())
                            )),
                            || PErrorKind::InvalidName(to.into())),
                        or_fail(
                            sp(tag("->")),
                            || PErrorKind::UnexpectedToken("->")),
                        or_fail(
                            alt((
                                pidentifier,
                                map(tag("*"),|x: PInput| x.into())
                            )),
                            || PErrorKind::InvalidName(to.into()))
                    )
                );
            _x: opt(tag(","));
            _x: or_fail(tag("}"),|| PErrorKind::UnterminatedDelimiter(b));
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
// impl<'src> PEnumExpression<'src> {
//     // extract the first token of the expression
//     pub fn token(&self) -> Token<'src> {
//         match self {
//             PEnumExpression::Compare(_, _, v) => *v,
//             PEnumExpression::And(a, _) => a.token(),
//             PEnumExpression::Or(a, _) => a.token(),
//             PEnumExpression::Not(a) => a.token(),
//             PEnumExpression::Default(t) => *t,
//         }
//     }
// }

fn penum_expression(i: PInput) -> PResult<PEnumExpression> {
    alt((
        enum_or_expression,
        enum_and_expression,
        enum_not_expression,
        map(tag("default"), |t| PEnumExpression::Default(Token::from(t))), // default looks like an atom so it must come first
        enum_atom,
    ))(i)
}
fn enum_atom(i: PInput) -> PResult<PEnumExpression> {
    alt((
        wsequence!(
            {
                t: tag("(");
                e: penum_expression;
                _x: or_fail(tag(")"), || PErrorKind::UnterminatedDelimiter(t));
            } => e
        ),
        wsequence!(
            {
                var: pvariable_identifier;
                _x: tag("=~");
                penum: opt(terminated(pidentifier, sp(tag(":"))));
                value: or_fail(pidentifier, || PErrorKind::InvalidEnumExpression);
            } => PEnumExpression::Compare(Some(var), penum, value)
        ),
        wsequence!(
            {
                var: pvariable_identifier;
                _x: tag("!~");
                penum: opt(terminated(pidentifier, sp(tag(":"))));
                value: or_fail(pidentifier, || PErrorKind::InvalidEnumExpression);
            } => PEnumExpression::Not(Box::new(PEnumExpression::Compare(Some(var), penum, value)))
        ),
        wsequence!(
            {
                penum: opt(terminated(pidentifier, sp(tag(":"))));
                value: pidentifier;
            } => PEnumExpression::Compare(None, penum, value)
        ),
    ))(i)
}
fn enum_or_expression(i: PInput) -> PResult<PEnumExpression> {
    wsequence!(
        {
            left: alt((enum_and_expression, enum_not_expression, enum_atom));
            _x: tag("||");
            right: or_fail(
                       alt((enum_or_expression, enum_and_expression, enum_not_expression, enum_atom)),
                       || PErrorKind::InvalidEnumExpression);
        } => PEnumExpression::Or(Box::new(left), Box::new(right))
    )(i)
}
fn enum_and_expression(i: PInput) -> PResult<PEnumExpression> {
    wsequence!(
        {
            left: alt((enum_not_expression, enum_atom));
            _x: tag("&&");
            right: or_fail(
                       alt((enum_and_expression, enum_not_expression, enum_atom)),
                       || PErrorKind::InvalidEnumExpression);
        } => PEnumExpression::And(Box::new(left), Box::new(right))
    )(i)
}
fn enum_not_expression(i: PInput) -> PResult<PEnumExpression> {
    wsequence!(
        {
            _x: tag("!");
            value: or_fail(enum_atom, || PErrorKind::InvalidEnumExpression);
        } => PEnumExpression::Not(Box::new(value))
    )(i)
}

/// An escaped string is a string delimited by '"' and that support backslash escapes.
/// The token is here to keep position
fn pescaped_string(i: PInput) -> PResult<(Token, String)> {
    // Add type annotation to help the type solver
    let f: fn(PInput) -> PResult<(Token, String)> = sequence!(
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
                            || PErrorKind::InvalidEscapeSequence
                        )
                    ));
            _x: or_fail(tag("\""), || PErrorKind::UnterminatedDelimiter(prefix));
        } => (prefix.into(), content)
    );
    f(i)
}

/// An unescaped string is a literal string delimited by '"""'.
/// The token is here to keep position
fn punescaped_string(i: PInput) -> PResult<(Token, String)> {
    sequence!(
        {
            prefix: tag("\"\"\"");
            content: map(
                         or_fail(take_until("\"\"\""), || PErrorKind::UnterminatedDelimiter(prefix)),
                         |x: PInput| x.to_string()
                    );
            _x: or_fail(tag("\"\"\""), || PErrorKind::UnterminatedDelimiter(prefix));
        } => (prefix.into(), content)
    )(i)
}

/// All strings should be interpolated
#[derive(Debug, PartialEq, Clone)]
pub enum PInterpolatedElement {
    Static(String),   // static content
    Variable(String), // variable name
}
fn pinterpolated_string(i: PInput) -> PResult<Vec<PInterpolatedElement>> {
    // There is a rest inside so this just serve as a guard
    all_consuming(alt((
        many1(alt((
            // $ constant
            value(PInterpolatedElement::Static("$".into()), tag("$$")),
            // variable
            sequence!(
                {
                    s: tag("${");
                    variable: or_fail(pvariable_identifier, || PErrorKind::InvalidVariableReference);
                    _x: or_fail(tag("}"), || PErrorKind::UnterminatedDelimiter(s));
                } => PInterpolatedElement::Variable(variable.fragment().into())
            ),
            // invalid $
            sequence!(
                {
                    _s: tag("$"); // $SomethingElse is an error
                    _x: or_fail(tag("$"), || PErrorKind::InvalidVariableReference); // $$ is already processed so this is an error
                } => PInterpolatedElement::Static("".into()) // this is mandatory but cannot happen
            ),
            // static data
            map(take_until("$"), |s: PInput| {
                PInterpolatedElement::Static(s.fragment.into())
            }),
            // end of string
            map(
                preceded(
                    peek(anychar), // do no take rest if we are already at the end
                    rest,
                ),
                |s: PInput| PInterpolatedElement::Static(s.fragment.into()),
            ),
        ))),
        // empty string
        value(vec![PInterpolatedElement::Static("".into())], not(anychar)),
    )))(i)
}

/// A PType is the type a variable or a parameter can take.
#[derive(Debug, PartialEq, Clone, Copy)]
pub enum PType {
    String,
    Number,
    Boolean,
    Struct,
    List,
}
fn ptype(i: PInput) -> PResult<PType> {
    alt((
        value(PType::String, tag("string")),
        value(PType::Number, tag("num")),
        value(PType::Boolean, tag("boolean")),
        value(PType::Struct, tag("struct")),
        value(PType::List, tag("list")),
    ))(i)
}

/// A number is currently represented by a float64
fn pnumber(i: PInput) -> PResult<(Token, f64)> {
    let (i, val) = recognize_float(i)?;
    match double::<&[u8], (&[u8], nom::error::ErrorKind)>(val.fragment.as_bytes()) {
        Err(_e) => panic!(format!("A parsed number canot be reparsed : {:?}", val)),
        Ok((_, n)) => Ok((i, (val.into(), n))),
    }
}

/// A list is stored in a Vec
fn plist(i: PInput) -> PResult<Vec<PValue>> {
    wsequence!(
        {
            s: tag("[");
            values: separated_list(sp(tag(",")),pvalue);
            _x: or_fail(tag("]"),|| PErrorKind::UnterminatedDelimiter(s));
        } => values
    )(i)
}

/// A struct is stored in a HashMap
fn pstruct(i: PInput) -> PResult<HashMap<String, PValue>> {
    wsequence!(
        {
            s: tag("{");
            values: separated_list(
                        sp(tag(",")),
                        separated_pair(pescaped_string, sp(tag(":")), pvalue)
                    );
            _x: or_fail(tag("}"),|| PErrorKind::UnterminatedDelimiter(s));
        } => values.into_iter().map(|(k,v)| (k.1,v)).collect()

    )(i)
}

/// PValue is a typed value of the content of a variable or a parameter.
/// Must be cloneable because it is copied during default values expansion
#[derive(Debug, PartialEq)]
pub enum PValue<'src> {
    String(Token<'src>, String),
    Number(Token<'src>, f64),
    EnumExpression(PEnumExpression<'src>),
    List(Vec<PValue<'src>>),
    Struct(HashMap<String, PValue<'src>>),
}
impl<'src> PValue<'src> {
    pub fn get_type(&self) -> PType {
        match self {
            PValue::String(_, _) => PType::String,
            PValue::Number(_, _) => PType::Number,
            PValue::EnumExpression(_) => PType::Boolean,
            PValue::Struct(_) => PType::Struct,
            PValue::List(_) => PType::List,
        }
    }
}
fn pvalue(i: PInput) -> PResult<PValue> {
    alt((
        // Be careful of ordering here
        map(punescaped_string, |(x, y)| PValue::String(x, y)),
        map(pescaped_string, |(x, y)| PValue::String(x, y)),
        map(pnumber, |(x, y)| PValue::Number(x, y)),
        map(penum_expression, PValue::EnumExpression),
        map(plist, PValue::List),
        map(pstruct, PValue::Struct),
    ))(i)
}

/// A metadata is a key/value pair that gives properties to the statement that follows.
/// Currently metadata is not used by the compiler, just parsed, but that may change.
#[derive(Debug, PartialEq)]
pub struct PMetadata<'src> {
    pub key: Token<'src>,
    pub value: PValue<'src>,
}
fn pmetadata(i: PInput) -> PResult<PMetadata> {
    wsequence!(
        {
            key: preceded(tag("@"), pidentifier);
            _x: or_fail(tag("="), || PErrorKind::UnexpectedToken("="));
            value: pvalue;
        } => PMetadata { key, value }
    )(i)
}

/// A parsed comment block starts with a ## and ends with the end of line.
/// Such comment is parsed and kept contrarily to comments starting with '#'.
fn pcomment(i: PInput) -> PResult<PMetadata> {
    let (i, start) = peek(tag("##"))(i)?;
    let (i, lines) = many1(map(
        preceded(
            tag("##"),
            alt((
                terminated(take_until("\n"), newline),
                // comment is the last line
                rest,
            )),
        ),
        |x: PInput| x.to_string(),
    ))(i)?;
    Ok((
        i,
        PMetadata {
            key: "comment".into(),
            value: PValue::String(start.into(), lines.join("\n")),
        },
    ))
}

/// A metadata list is an optional list of metadata entries
/// Comments are considered to be metadata
fn pmetadata_list(i: PInput) -> PResult<Vec<PMetadata>> {
    many0(alt((pmetadata, pcomment)))(i)
}

/// A parameters defines how a parameter can be passed.
/// Its is of the form name:type=default where type and default are optional.
/// Type can be guessed from default.
#[derive(Debug, PartialEq)]
pub struct PParameter<'src> {
    pub name: Token<'src>,
    pub ptype: Option<PType>,
}
// return a pair because we will store the default value separately
fn pparameter(i: PInput) -> PResult<(PParameter, Option<PValue>)> {
    wsequence!(
        {
            name: pidentifier;
            ptype: opt(
                    wsequence!(
                        {
                            _t: tag(":");
                            ty: or_fail(ptype,|| PErrorKind::ExpectedKeyword("type"));
                        } => ty)
                    );
            default: opt(
                    wsequence!(
                        {
                            _t: tag("=");
                            val: or_fail(pvalue,|| PErrorKind::ExpectedKeyword("value"));
                        } => val)
                    );
        } => (PParameter { ptype, name }, default)
    )(i)
}

/// A resource definition defines how a resource is uniquely identified.
#[derive(Debug, PartialEq)]
pub struct PResourceDef<'src> {
    pub metadata: Vec<PMetadata<'src>>,
    pub name: Token<'src>,
    pub parameters: Vec<PParameter<'src>>,
}
// separate default parameters and parents because they are stored separately
fn presource_def(i: PInput) -> PResult<(PResourceDef, Vec<Option<PValue>>, Option<Token>)> {
    wsequence!(
        {
            metadata: pmetadata_list;
            _x: tag("resource"); // TODO at least one space here
            name: pidentifier;
            s: tag("(");
            parameter_list: separated_list(sp(tag(",")), pparameter);
            _x: or_fail(tag(")"), || PErrorKind::UnterminatedDelimiter(s));
            parent: opt(preceded(sp(tag(":")),pidentifier));
        } => {
            let (parameters, parameter_defaults) = parameter_list.into_iter().unzip();
            (PResourceDef {
                      metadata,
                      name,
                      parameters,
            },
            parameter_defaults,
            parent)
        }
    )(i)
}

/// A resource reference identifies a unique resource.
fn presource_ref(i: PInput) -> PResult<(Token, Vec<PValue>)> {
    wsequence!(
        {
            name: pidentifier;
            params: opt(wsequence!(
                        {
                            t: tag("(");
                            parameters: separated_list(sp(tag(",")), pvalue);
                            _x: or_fail(tag(")"), || PErrorKind::UnterminatedDelimiter(t));
                        } => parameters
                    ));
        } => (name, params.unwrap_or_else(Vec::new))
    )(i)
}

/// A variable definition is a var=value
fn pvariable_definition(i: PInput) -> PResult<(Token, PValue)> {
    wsequence!(
        {
            variable: pidentifier;
            _t: tag("=");
            value: or_fail(pvalue, || PErrorKind::ExpectedKeyword("value"));
        } => (variable, value)
    )(i)
}

/// A call mode tell how a state must be applied
#[derive(Debug, PartialEq, Clone)]
pub enum PCallMode {
    Enforce,
    Condition,
    Audit,
}
fn pcall_mode(i: PInput) -> PResult<PCallMode> {
    alt((
        value(PCallMode::Condition, tag("?")),
        value(PCallMode::Audit, tag("!")),
        value(PCallMode::Enforce, peek(anychar)),
    ))(i)
}

/// A State Declaration is a given required state on a given resource
#[derive(Debug, PartialEq)]
pub struct PStateDeclaration<'src> {
    pub metadata: Vec<PMetadata<'src>>,
    pub mode: PCallMode,
    pub resource: Token<'src>,
    pub resource_params: Vec<PValue<'src>>,
    pub state: Token<'src>,
    pub state_params: Vec<PValue<'src>>,
    pub outcome: Option<Token<'src>>,
}
fn pstate_declaration(i: PInput) -> PResult<PStateDeclaration> {
    wsequence!(
        {
            metadata: pmetadata_list;
            mode: pcall_mode;
            resource: presource_ref;
            _t: tag(".");
            state: pidentifier;
            s: tag("(");
            state_params: separated_list( sp(tag(",")), pvalue );
            _x: or_fail(tag(")"), || PErrorKind::UnterminatedDelimiter(s));
            outcome: opt(preceded(sp(tag("as")),pidentifier));
        } => PStateDeclaration {
                metadata,
                mode,
                resource: resource.0,
                resource_params: resource.1,
                state,
                state_params,
                outcome
        }
    )(i)
}

/// A statement is the atomic element of a state definition.
#[derive(Debug, PartialEq)]
pub enum PStatement<'src> {
    VariableDefinition(Vec<PMetadata<'src>>, Token<'src>, PValue<'src>),
    StateDeclaration(PStateDeclaration<'src>),
    //   case keyword, list (condition   ,       then)
    Case(
        Token<'src>,
        Vec<(PEnumExpression<'src>, Vec<PStatement<'src>>)>,
    ), // keep the pinput since it will be reparsed later
    // Stop engine with a final message
    Fail(PValue<'src>),
    // Inform the user of something
    Log(PValue<'src>),
    // Return a specific outcome
    Return(Token<'src>),
    // Do nothing
    Noop,
}
fn pstatement(i: PInput) -> PResult<PStatement> {
    alt((
        // One state
        map(pstate_declaration, PStatement::StateDeclaration),
        // Variable definition
        map(
            pair(pmetadata_list, pvariable_definition),
            |(metadata, (variable, value))| {
                PStatement::VariableDefinition(metadata, variable, value)
            },
        ),
        // case
        wsequence!(
            {
                metadata: pmetadata_list; // metadata is invalid here, check it after the 'case' tag below
                case: tag("case");
                _fail: or_fail(verify(peek(anychar), |_| metadata.is_empty()), || PErrorKind::UnsupportedMetadata(metadata[0].key.into()));
                s: tag("{");
                cases: separated_list(sp(tag(",")),
                        wsequence!(
                            {
                                expr: or_fail(penum_expression, || PErrorKind::ExpectedKeyword("enum expression"));
                                _x: or_fail(tag("=>"), || PErrorKind::UnexpectedToken("=>"));
                                stmt: or_fail(alt((
                                    map(pstatement, |x| vec![x]),
                                    wsequence!(
                                        {
                                            s: tag("{");
                                            vec: many0(pstatement);
                                            _x: or_fail(tag("}"),|| PErrorKind::UnterminatedDelimiter(s));
                                        } => vec
                                    ),
                                )), || PErrorKind::ExpectedKeyword("statement"));
                            } => (expr,stmt)
                        ));
                _x: opt(tag(","));
                _x: or_fail(tag("}"),|| PErrorKind::UnterminatedDelimiter(s));
            } => PStatement::Case(case.into(), cases)
        ),
        // if
        wsequence!(
            {
                metadata: pmetadata_list; // metadata is invalid here, check it after the 'if' tag below
                case: tag("if");// TODO at least one space here
                expr: or_fail(penum_expression, || PErrorKind::ExpectedKeyword("enum expression"));
                _x: or_fail(tag("=>"), || PErrorKind::UnexpectedToken("=>"));
                stmt: or_fail(pstatement, || PErrorKind::ExpectedKeyword("statement"));
            } => {
                // Propagate metadata to the single statement
                let statement = match stmt {
                    PStatement::StateDeclaration(mut sd) => {
                        sd.metadata.extend(metadata);
                        PStatement::StateDeclaration(sd)
                    },
                    x => x,
                };
                PStatement::Case(case.into(), vec![(expr,vec![statement]), (PEnumExpression::Default("default".into()),vec![PStatement::Noop])] )
            }
        ),
        // Flow statements
        map(
            preceded(sp(tag("return")), pvariable_identifier),
            PStatement::Return,
        ), // TODO at least one space here
        map(preceded(sp(tag("fail")), pvalue), PStatement::Fail), // TODO at least one space here
        map(preceded(sp(tag("log")), pvalue), PStatement::Log),   // TODO at least one space here
        map(tag("noop"), |_| PStatement::Noop),
    ))(i)
}

/// A state definition defines a state of a resource.
/// It is composed of one or more statements.
#[derive(Debug, PartialEq)]
pub struct PStateDef<'src> {
    pub metadata: Vec<PMetadata<'src>>,
    pub name: Token<'src>,
    pub resource_name: Token<'src>,
    pub parameters: Vec<PParameter<'src>>,
    pub statements: Vec<PStatement<'src>>,
}
// separate parameter defaults since they will be stored separately
fn pstate_def(i: PInput) -> PResult<(PStateDef, Vec<Option<PValue>>)> {
    wsequence!(
        {
            metadata: pmetadata_list;
            resource_name: pidentifier;
            _st: tag("state"); // TODO at least one space here
            name: pidentifier;
            s: or_fail(tag("("), || PErrorKind::UnexpectedToken("("));
            parameter_list: separated_list(sp(tag(",")), pparameter);
            _x: or_fail(tag(")"), || PErrorKind::UnterminatedDelimiter(s));
            sb: or_fail(tag("{"), || PErrorKind::UnexpectedToken("{"));
            statements: many0(pstatement);
            _x: or_fail(tag("}"), || PErrorKind::UnterminatedDelimiter(sb));
        } => {
            let (parameters, parameter_defaults) = parameter_list.into_iter().unzip();
            (PStateDef {
                metadata,
                name,
                resource_name,
                parameters,
                statements,
            },
            parameter_defaults)
        }
    )(i)
}

#[derive(Debug, PartialEq)]
pub struct PAliasDef<'src> {
    metadata: Vec<PMetadata<'src>>,
    resource_alias: Token<'src>,
    resource_alias_parameters: Vec<Token<'src>>,
    state_alias: Token<'src>,
    state_alias_parameters: Vec<Token<'src>>,
    resource: Token<'src>,
    resource_parameters: Vec<Token<'src>>,
    state: Token<'src>,
    state_parameters: Vec<Token<'src>>,
}
fn palias_def(i: PInput) -> PResult<PAliasDef> {
    wsequence!(
        {
            metadata: pmetadata_list;
            _x: tag("alias"); // TODO at least one space here
            resource_alias: pidentifier;
            resource_alias_parameters: delimited(sp(tag("(")),separated_list(sp(tag(",")),pidentifier),sp(tag(")")));
            _x: tag(".");
            state_alias: pidentifier;
            state_alias_parameters: delimited(sp(tag("(")),separated_list(sp(tag(",")),pidentifier),sp(tag(")")));
            _x: tag("=");
            resource: pidentifier;
            resource_parameters: delimited(sp(tag("(")),separated_list(sp(tag(",")),pidentifier),sp(tag(")")));
            _x: tag(".");
            state: pidentifier;
            state_parameters: delimited(sp(tag("(")),separated_list(sp(tag(",")),pidentifier),sp(tag(")")));
        } => PAliasDef {metadata, resource_alias, resource_alias_parameters,
                        state_alias, state_alias_parameters,
                        resource, resource_parameters,
                        state, state_parameters }
    )(i)
}

/// A declaration is one of the a top level elements that can be found anywhere in the file.
#[derive(Debug, PartialEq)]
pub enum PDeclaration<'src> {
    Enum(PEnum<'src>),
    Mapping(PEnumMapping<'src>),
    Resource(
        (
            PResourceDef<'src>,
            Vec<Option<PValue<'src>>>,
            Option<Token<'src>>,
        ),
    ),
    State((PStateDef<'src>, Vec<Option<PValue<'src>>>)),
    GlobalVar((Token<'src>, PValue<'src>)),
    Alias(PAliasDef<'src>),
}
fn pdeclaration(i: PInput) -> PResult<PDeclaration> {
    alt((
        map(penum, PDeclaration::Enum),
        map(penum_mapping, PDeclaration::Mapping),
        map(presource_def, PDeclaration::Resource),
        map(pstate_def, PDeclaration::State),
        map(pvariable_definition, PDeclaration::GlobalVar),
        map(palias_def, PDeclaration::Alias),
    ))(i)
}

/// A PFile is the result of a single file parsing
/// It contains a valid header and top level declarations.
#[derive(Debug, PartialEq)]
pub struct PFile<'src> {
    pub header: PHeader,
    pub code: Vec<PDeclaration<'src>>,
}
fn pfile(i: PInput) -> PResult<PFile> {
    all_consuming(sequence!(
        {
            header: pheader;
            _x: strip_spaces_and_comment;
            code: many0(pdeclaration);
            _x: strip_spaces_and_comment;
        } => PFile {header, code}
    ))(i)
}

// tests must be at the end to be able to test macros
#[cfg(test)]
pub mod tests; // pub for use by other tests only
