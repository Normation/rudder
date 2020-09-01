// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

mod baseparsers;
mod error;
mod token;

use nom::{
    branch::*, bytes::complete::*, character::complete::*, combinator::*, error::*, multi::*,
    number::complete::*, sequence::*,
};
use toml::Value as TomlValue;

use std::collections::HashMap;

use crate::error::*;
use crate::{sequence, wsequence}; // macros are exported at the root of the crate
use baseparsers::*;
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

/// PAST is just a global structure that contain all parsed data sorted sequentially per type.
#[derive(Debug, Default)]
pub struct PAST<'src> {
    pub enums: Vec<PEnum<'src>>,
    pub sub_enums: Vec<PSubEnum<'src>>,
    pub enum_aliases: Vec<PEnumAlias<'src>>,
    pub resources: Vec<PResourceDef<'src>>,
    pub states: Vec<PStateDef<'src>>,
    pub variable_definitions: Vec<PVariableDef<'src>>,
    pub variable_extensions: Vec<PVariableExt<'src>>,
    pub variable_declarations: Vec<PVariableDecl<'src>>,
    pub parameter_defaults: Vec<(Token<'src>, Option<Token<'src>>, Vec<Option<PValue<'src>>>)>, // separate parameter defaults since they will be processed first
    pub parents: Vec<(Token<'src>, Token<'src>)>,
    pub aliases: Vec<PAliasDef<'src>>,
}

impl<'src> PAST<'src> {
    pub fn new() -> PAST<'static> {
        PAST::default()
    }

    /// The parse function that should be called to parse a file
    pub fn add_file(&mut self, filename: &'src str, content: &'src str) -> Result<()> {
        let pfile = fix_error_type(pfile(PInput::new_extra(content, filename)))?;
        if pfile.header.version != 0 {
            return Err(Error::new(format!(
                "Format not supported yet: {}",
                pfile.header.version
            )));
        }
        pfile
            .code
            .into_iter()
            .for_each(|declaration| match declaration {
                PDeclaration::Enum(e) => self.enums.push(e),
                PDeclaration::SubEnum(e) => self.sub_enums.push(e),
                PDeclaration::EnumAlias(e) => self.enum_aliases.push(e),
                PDeclaration::Resource((r, d, p)) => {
                    self.parameter_defaults.push((r.name, None, d));
                    if let Some(parent) = p {
                        self.parents.push((r.name, parent))
                    };
                    self.resources.push(r);
                }
                PDeclaration::State((s, d)) => {
                    self.parameter_defaults
                        .push((s.resource_name, Some(s.name), d));
                    self.states.push(s);
                }
                PDeclaration::GlobalVar(kv) => self.variable_definitions.push(kv),
                PDeclaration::GlobalVarExt(kv) => self.variable_extensions.push(kv),
                PDeclaration::MagicVar(kv) => self.variable_declarations.push(kv),
                PDeclaration::Alias(a) => self.aliases.push(a),
            });
        Ok(())
    }
}

/// Parse a string for interpolation
pub fn parse_string(content: &str) -> Result<Vec<PInterpolatedElement>> {
    fix_error_type(pinterpolated_string(PInput::new_extra(content, "")))
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
            _x: opt(tuple((etag("#!/"), take_until("\n"), newline)));
            _x: or_fail(sp(etag("@format")), || PErrorKind::InvalidFormat);
            _x: or_fail(sp(etag("=")), || PErrorKind::InvalidFormat);
            version: or_fail(
                map_res(take_until("\n"), |s: PInput| s.fragment().parse::<u32>()),
                || PErrorKind::InvalidFormat
            );
            _x: etag("\n");
        } => PHeader { version }
    )(i)
}

/// An enum item can be either a classic identifier or a *
fn penum_item(i: PInput) -> PResult<(Vec<PMetadata>, Token)> {
    wsequence!(
        {
            metadata: pmetadata_list;
            identifier: alt((
                pidentifier,
                map(tag("*"), |x: PInput| x.into()),
            ));
        } => (metadata, identifier)
    )(i)
}

/// An enum is a list of values, like a C enum.
/// An enum can be global, which means its values are globally unique and can be guessed without specifying type.
/// A global enum also has a matching global variable with the same name as its type.
#[derive(Debug, PartialEq)]
pub struct PEnum<'src> {
    pub global: bool,
    pub metadata: Vec<PMetadata<'src>>,
    pub name: Token<'src>,
    pub items: Vec<(Vec<PMetadata<'src>>, Token<'src>)>,
}
fn penum(i: PInput) -> PResult<PEnum> {
    wsequence!(
        {
            metadata: pmetadata_list;
            global: opt(estag("global"));
            e:      estag("enum");
            name:   or_fail(pidentifier, || PErrorKind::InvalidName(e));
            items : delimited_nonempty_list("{", penum_item, ",", "}");
        } => PEnum {
            metadata,
            global: global.is_some(),
            name,
            items,
        }
    )(i)
}

/// A sub enum is an extension of an existing enum, t adds children to an existing enum item
#[derive(Debug, PartialEq)]
pub struct PSubEnum<'src> {
    pub name: Token<'src>,
    pub enum_name: Option<Token<'src>>,
    pub items: Vec<(Vec<PMetadata<'src>>, Token<'src>)>,
}
fn psub_enum(i: PInput) -> PResult<PSubEnum> {
    wsequence!(
        {
            metadata: pmetadata_list; // metadata unsupported here, check done after 'enum' tag
            e:      estag("items");
            _i:     estag("in");
            _fail:  or_fail(verify(peek(anychar), |_| metadata.is_empty()), || PErrorKind::UnsupportedMetadata(metadata[0].source.into()));
            enum_name: opt(terminated(pidentifier,etag(".")));
            name:   or_fail(pidentifier, || PErrorKind::InvalidName(e));
            items : delimited_nonempty_list("{", penum_item, ",", "}");
        } => PSubEnum {
                name,
                enum_name,
                items,
        }
    )(i)
}

/// An enum alias gives the ability to give another name to an enum item
#[derive(Debug, PartialEq)]
pub struct PEnumAlias<'src> {
    pub name: Token<'src>, // new name
    pub enum_name: Option<Token<'src>>,
    pub item: Token<'src>, // original name
}
fn penum_alias(i: PInput) -> PResult<PEnumAlias> {
    wsequence!(
        {
            metadata: pmetadata_list; // metadata unsupported here, check done after 'enum' tag
            e:      estag("enum");
            _i:     estag("alias");
            _fail:  or_fail(verify(peek(anychar), |_| metadata.is_empty()), || PErrorKind::UnsupportedMetadata(metadata[0].source.into()));
            name:   or_fail(pidentifier, || PErrorKind::InvalidName(e));
            _x:     ftag("=");
            enum_name: opt(terminated(pidentifier,etag(".")));
            item:   or_fail(pidentifier, || PErrorKind::InvalidName(e));
        } => PEnumAlias {
            name,
            enum_name,
            item,
        }
    )(i)
}

/// An enum expression is used as a condition in a case expression.
/// This is a boolean expression based on enum comparison.
/// A comparison check if the variable is of the right type and contains
/// the provided item as a value, or an ancestor item if this is an enum tree.
/// 'default' is a value that is equivalent of 'true'.
#[derive(Debug, PartialEq, Clone)]
pub struct PEnumExpression<'src> {
    pub source: Token<'src>,
    pub expression: PEnumExpressionPart<'src>,
}
fn penum_expression(i: PInput) -> PResult<PEnumExpression> {
    penum_expression_part(i).map(|(rest, expression)| {
        let source = get_parsed_context(i, i, rest);
        (rest, PEnumExpression { source, expression })
    })
}

#[derive(Debug, PartialEq, Clone)]
#[allow(clippy::large_enum_variant)]
pub enum PEnumExpressionPart<'src> {
    //             variable             enum name     value/item
    Compare(Option<Token<'src>>, Option<Token<'src>>, Token<'src>),
    //                  variable             enum name            range start          range end     position in case everything else is None
    RangeCompare(
        Option<Token<'src>>,
        Option<Token<'src>>,
        Option<Token<'src>>,
        Option<Token<'src>>,
        Token<'src>,
    ),
    And(
        Box<PEnumExpressionPart<'src>>,
        Box<PEnumExpressionPart<'src>>,
    ),
    Or(
        Box<PEnumExpressionPart<'src>>,
        Box<PEnumExpressionPart<'src>>,
    ),
    Not(Box<PEnumExpressionPart<'src>>),
    Default(Token<'src>),
    NoDefault(Token<'src>),
}

fn penum_expression_part(i: PInput) -> PResult<PEnumExpressionPart> {
    alt((
        enum_or_expression,
        enum_and_expression,
        enum_not_expression,
        map(etag("default"), |t| {
            PEnumExpressionPart::Default(Token::from(t))
        }), // default looks like an atom so it must come first
        enum_atom,
    ))(i)
}
enum RangeOrItem<'src> {
    //           enum name            range start          range end     position in case everything else is None
    Range(
        Option<Token<'src>>,
        Option<Token<'src>>,
        Option<Token<'src>>,
        Token<'src>,
    ),
    //           enum name    item
    Item(Option<Token<'src>>, Token<'src>),
}
fn enum_range_or_item(i: PInput) -> PResult<RangeOrItem> {
    alt((
        // we must separate range with and without enum name otherwise the first dot would ne considered as the range separator
        // iow there is no backtracking within a sequence
        wsequence!(
            {
                enum_name: terminated(pidentifier,etag("."));
                left: pidentifier;
                dots: etag("..");
                right: opt(pidentifier);
            } => RangeOrItem::Range(Some(enum_name),Some(left),right,dots.into())
        ),
        wsequence!(
            {
                dots: etag("..");
                enum_name: terminated(pidentifier,etag("."));
                right: opt(pidentifier);
            } => RangeOrItem::Range(Some(enum_name),None,right,dots.into())
        ),
        wsequence!(
            {
                left: opt(pidentifier);
                dots: etag("..");
                right: opt(pidentifier);
            } => RangeOrItem::Range(None,left,right,dots.into())
        ),
        wsequence!(
            {
                enum_name: opt(terminated(pidentifier,etag(".")));
                item: pidentifier;
            } => RangeOrItem::Item(enum_name,item)
        ),
    ))(i)
}
fn enum_atom(i: PInput) -> PResult<PEnumExpressionPart> {
    alt((
        delimited_parser("(", penum_expression_part, ")"),
        wsequence!(
            {
                var: pvariable_identifier;
                _x: etag("=~");
                value: or_fail(enum_range_or_item, || PErrorKind::InvalidEnumExpression);
            } => {
                match value {
                    RangeOrItem::Range(name,left,right,dots) => PEnumExpressionPart::RangeCompare(Some(var), name, left, right, dots),
                    RangeOrItem::Item(name,val) => PEnumExpressionPart::Compare(Some(var), name, val),
                }
            }
        ),
        wsequence!(
            {
                var: pvariable_identifier;
                _x: etag("!~");
                value: or_fail(enum_range_or_item, || PErrorKind::InvalidEnumExpression);
            } => {
                match value {
                    RangeOrItem::Range(name,left,right,dots) => PEnumExpressionPart::Not(Box::new(PEnumExpressionPart::RangeCompare(Some(var), name, left, right, dots))),
                    RangeOrItem::Item(name, val) => PEnumExpressionPart::Not(Box::new(PEnumExpressionPart::Compare(Some(var), name, val))),
                }
            }
        ),
        map(enum_range_or_item, |value| match value {
            RangeOrItem::Range(name, left, right, dots) => {
                PEnumExpressionPart::RangeCompare(None, name, left, right, dots)
            }
            RangeOrItem::Item(name, val) => PEnumExpressionPart::Compare(None, name, val),
        }),
    ))(i)
}
fn enum_or_expression(i: PInput) -> PResult<PEnumExpressionPart> {
    wsequence!(
        {
            left: alt((enum_and_expression, enum_not_expression, enum_atom));
            _x: etag("|");
            right: or_fail(
                       alt((enum_or_expression, enum_and_expression, enum_not_expression, enum_atom)),
                       || PErrorKind::InvalidEnumExpression);
        } => PEnumExpressionPart::Or(Box::new(left), Box::new(right))
    )(i)
}
fn enum_and_expression(i: PInput) -> PResult<PEnumExpressionPart> {
    wsequence!(
        {
            left: alt((enum_not_expression, enum_atom));
            _x: etag("&");
            right: or_fail(
                       alt((enum_and_expression, enum_not_expression, enum_atom)),
                       || PErrorKind::InvalidEnumExpression);
        } => PEnumExpressionPart::And(Box::new(left), Box::new(right))
    )(i)
}
fn enum_not_expression(i: PInput) -> PResult<PEnumExpressionPart> {
    wsequence!(
        {
            _x: etag("!");
            value: or_fail(enum_atom, || PErrorKind::InvalidEnumExpression);
        } => PEnumExpressionPart::Not(Box::new(value))
    )(i)
}

/// An escaped string is a string delimited by '"' and that support backslash escapes.
/// The token is here to keep position
fn pescaped_string(i: PInput) -> PResult<(Token, String)> {
    // Add type annotation to help the type solver
    let f: fn(PInput) -> PResult<(Token, String)> = sequence!(
        {
            prefix: etag("\"");
            content: alt((
                        // empty lines are not properly handled by escaped_transform
                        // so we detect them here beforehand
                        peek(value("".into(), etag("\""))),
                        or_fail(
                            escaped_transform(
                                take_till1(|c: char| (c == '\\')||(c == '"')),
                                '\\',
                                alt((
                                   value("\\", etag("\\")),
                                   value("\"", etag("\"")),
                                   value("\n", etag("n")),
                                   value("\r", etag("r")),
                                   value("\t", etag("t")),
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
            prefix: etag("\"\"\"");
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
            value(PInterpolatedElement::Static("$".into()), etag("$$")),
            // variable
            sequence!(
                {
                    s: etag("${");
                    variable: or_fail(pvariable_identifier, || PErrorKind::InvalidVariableReference);
                    _x: or_fail(tag("}"), || PErrorKind::UnterminatedDelimiter(s));
                } => PInterpolatedElement::Variable(variable.fragment().into())
            ),
            // invalid $
            sequence!(
                {
                    _s: etag("$"); // $SomethingElse is an error
                    _x: or_fail(tag("$"), || PErrorKind::InvalidVariableReference); // $$ is already processed so this is an error
                } => PInterpolatedElement::Static("".into()) // this is mandatory but cannot happen
            ),
            // static data
            map(take_until("$"), |s: PInput| {
                PInterpolatedElement::Static((*s.fragment()).into())
            }),
            // end of string
            map(
                preceded(
                    peek(anychar), // do no take rest if we are already at the end
                    rest,
                ),
                |s: PInput| PInterpolatedElement::Static((*s.fragment()).into()),
            ),
        ))),
        // empty string
        value(vec![PInterpolatedElement::Static("".into())], not(anychar)),
    )))(i)
}

/// A number is currently represented by a float64
fn pnumber(i: PInput) -> PResult<(Token, f64)> {
    let (i, val) = recognize_float(i)?;
    #[allow(clippy::match_wild_err_arm)]
    match double::<&[u8], (&[u8], nom::error::ErrorKind)>(val.fragment().as_bytes()) {
        Err(_) => panic!(format!("A parsed number cannot be reparsed: {:?}", val)),
        Ok((_, n)) => Ok((i, (val.into(), n))),
    }
}

/// A list is stored in a Vec TODO
fn plist(i: PInput) -> PResult<Vec<PValue>> {
    wsequence!(
        {
            // s: tag("[");
            // values: separated_list(sp(etag(",")), pvalue);
            // _x: or_fail(peek(is_not(",")), || PErrorKind::ExpectedToken("parameter"));
            // _x: or_fail(tag("]"),|| PErrorKind::UnterminatedDelimiter(s));
            values: delimited_list("[", pvalue, ",", "]");
        } => values
    )(i)
}

/// A struct is stored in a HashMap TODO
fn pstruct(i: PInput) -> PResult<HashMap<String, PValue>> {
    map(
        delimited_list(
            "{",
            |j| separated_pair(pescaped_string, sp(etag(":")), pvalue)(j),
            ",",
            "}",
        ),
        |l| l.into_iter().map(|(k, v)| (k.1, v)).collect(),
    )(i)
}

/// Alternative version of pstruct based on "." rather than braces.
/// Used for the agent
/// A struct is stored in a HashMap
// fn pstruct_agent(i: PInput) -> PResult<HashMap<String, PValue>> {
//     wsequence!(
//         {
//             values: separated_list(
//                         sp(etag(".")),
//                         pvalue
//                     );
//         } => values.into_iter().map(|(k,v)| (k.1,v)).collect()
//     )(i)
// }

/// A PType is the type a variable or a parameter can take.
/// Its only purpose is to be a PValue construction helper
use std::marker::PhantomData;
#[derive(Debug, PartialEq, Copy, Clone)]
pub enum PType<'src> {
    String,
    Number,
    Boolean,
    Struct,
    List,
    Phantom(PhantomData<Token<'src>>), // phantomdata to force lifetime since we'll add some soon
}
/// PValue is a typed value of the content of a variable or a parameter.
/// Must be cloneable because it is copied during default values expansion
// TODO sep,arate value from type and handle automatic values (variable declaration)
#[derive(Debug, PartialEq, Clone)]
#[allow(clippy::large_enum_variant)]
pub enum PValue<'src> {
    String(Token<'src>, String),
    Number(Token<'src>, f64),
    Boolean(Token<'src>, bool),
    EnumExpression(PEnumExpression<'src>),
    Struct(HashMap<String, PValue<'src>>),
    List(Vec<PValue<'src>>),
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

// TODO missing complex struct parser
fn ptype(i: PInput) -> PResult<PType> {
    alt((
        value(PType::String, etag("string")),
        value(PType::Number, etag("num")),
        value(PType::Boolean, etag("boolean")),
        value(PType::Struct, etag("struct")),
        value(PType::List, etag("list")),
    ))(i)
}

/// A complex value is a generic RValue, ie anything that is equivalent to a value.
/// Currently only supported in let variables
#[derive(Debug, PartialEq, Clone)]
pub struct PComplexValue<'src> {
    pub source: Token<'src>,
    // nested complex values not supported
    pub cases: Vec<(PEnumExpression<'src>, Option<PValue<'src>>)>,
}
/// A single case in a case switch
fn pvalue_case(i: PInput) -> PResult<(PEnumExpression, Option<PValue>)> {
    alt((
        map(etag("nodefault"), |t| {
            (
                PEnumExpression {
                    source: Token::from(t),
                    expression: PEnumExpressionPart::NoDefault(Token::from(t)),
                },
                None,
            )
        }),
        wsequence!(
            {
                expr: or_fail(penum_expression, || PErrorKind::ExpectedKeyword("enum expression"));
                _x: ftag("=>");
                value: or_fail(pvalue, || PErrorKind::ExpectedToken("case statement"));
            } => (expr,Some(value))
        ),
    ))(i)
}
fn pcomplex_value(i: PInput) -> PResult<PComplexValue> {
    alt((
        wsequence!(
            {
                metadata: pmetadata_list; // metadata is invalid here, check it after the 'if' tag below
                case: estag("if");
                _fail: or_fail(verify(peek(anychar), |_| metadata.is_empty()), || PErrorKind::UnsupportedMetadata(metadata[0].source.into()));
                value_case: pvalue_case;
                :source(case..)
            } => PComplexValue { source, cases: vec![value_case] }
        ),
        wsequence!(
            {
                metadata: pmetadata_list; // metadata is invalid here, check it after the 'case' tag below
                case: etag("case");
                _fail: or_fail(verify(peek(anychar), |_| metadata.is_empty()), || PErrorKind::UnsupportedMetadata(metadata[0].source.into()));
                cases: delimited_list("{", pvalue_case, ",", "}" );
                :source(case..)
            } => PComplexValue { source, cases }
        ),
        |i| {
            let (j, res) = pvalue(i)?;
            let source = get_parsed_context(i, i, j);
            Ok((j, PComplexValue {
                source,
                cases: vec![(
                    PEnumExpression {
                        source: "".into(),
                        expression: PEnumExpressionPart::Default("".into()),
                    },
                    Some(res)
                )],
            }))
        }
    ))(i)
}

/// A metadata is a key/value pair that gives properties to the statement that follows.
/// Currently metadata is not used by the compiler, just parsed, but that may change.
#[derive(Debug, PartialEq)]
pub struct PMetadata<'src> {
    pub source: Token<'src>,
    pub values: TomlValue,
}
fn pmetadata(i: PInput) -> PResult<PMetadata> {
    let (i0, _) = strip_spaces_and_comment(i)?;
    let mut it = iterator(i0, delimited(tag("@"), not_line_ending, line_ending));
    let metadata_string = it.map(|v| *v.fragment()).collect::<Vec<&str>>().join("\n");
    let (rest, _) = it.finish()?;
    if &metadata_string == "" {
        return Err(nom::Err::Error(PError {
            context: None,
            kind: PErrorKind::NoMetadata,
        }));
    }

    let values = match toml::de::from_str(&metadata_string) {
        Ok(v) => v,
        Err(e) => {
            return Err(nom::Err::Error(PError {
                context: None,
                kind: PErrorKind::TomlError(i, e),
            }))
        }
    };

    let source = get_parsed_context(i, i0, rest);
    let (rest, _) = strip_spaces_and_comment(rest)?;
    Ok((rest, PMetadata { source, values }))
}

/// A parsed comment block starts with a ## and ends with the end of line.
/// Such comment is parsed and kept contrarily to comments starting with '#'.
fn pcomment(i: PInput) -> PResult<PMetadata> {
    let i0 = i;
    let (i, lines) = many1(map(
        preceded(
            etag("##"),
            alt((
                terminated(take_until("\n"), newline),
                // comment is the last line
                rest,
            )),
        ),
        |x: PInput| x.to_string(),
    ))(i)?;
    let source = get_parsed_context(i0, i0, i);
    let mut data = toml::map::Map::new();
    data.insert("comment".into(), TomlValue::String(lines.join("\n")));
    Ok((
        i,
        PMetadata {
            source,
            values: TomlValue::Table(data),
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
    pub ptype: Option<PType<'src>>,
}
// return a pair because we will store the default value separately
fn pparameter(i: PInput) -> PResult<(PParameter, Option<PValue>)> {
    wsequence!(
        {
            name: pidentifier;
            ptype: opt(
                    wsequence!(
                        {
                            _t: etag(":");
                            ty: or_fail(ptype,|| PErrorKind::ExpectedKeyword("type"));
                        } => ty)
                    );
            default: opt(
                    wsequence!(
                        {
                            _t: etag("=");
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
    pub variable_definitions: Vec<PVariableDef<'src>>,
    pub variable_extensions: Vec<PVariableExt<'src>>,
}
// separate default parameters and parents because they are stored separately
fn presource_def(i: PInput) -> PResult<(PResourceDef, Vec<Option<PValue>>, Option<Token>)> {
    wsequence!(
        {
            metadata: pmetadata_list;
            _x: estag("resource");
            name: pidentifier;
            param_list: delimited_list("(", pparameter, ",", ")");
            parent: opt(preceded(sp(etag(":")),pidentifier));
            vars: presource_body;
        } => {
            let (parameters, parameter_defaults) = param_list.into_iter().unzip();
            (PResourceDef {
                      metadata,
                      name,
                      parameters,
                      variable_definitions: vars.0,
                      variable_extensions: vars.1,
            },
            parameter_defaults,
            parent)
        }
    )(i)
}

/// A resource reference identifies a unique resource.
fn presource_body(i: PInput) -> PResult<(Vec<PVariableDef>, Vec<PVariableExt>)> {
    enum BodyVar<'src> { Def(PVariableDef<'src>), Ext(PVariableExt<'src>) }
    map(opt(delimited(
            sp(etag("{")),
            many0(alt((
                map(pvariable_definition, |x| BodyVar::Def(x)),
                map(pvariable_extension, |x| BodyVar::Ext(x)),
            ))),
            sp(etag("}"))
        )),
        |x| {
            match x {
                None => (Vec::new(), Vec::new()),
                Some(list) => {
                    let(def, ext) : (Vec<BodyVar>,Vec<BodyVar>) = list.into_iter().partition(|x| match x { BodyVar::Def(_) => true, _=> false });
                    let def = def.into_iter().map(|x| match x { BodyVar::Def(d) => d, _ => panic!("BUG") }).collect::<Vec<PVariableDef>>();
                    let ext = ext.into_iter().map(|x| match x { BodyVar::Ext(d) => d, _ => panic!("BUG") }).collect::<Vec<PVariableExt>>();
                    (def, ext)
                },
            }
        }
    )(i)
}

/// A resource reference identifies a unique resource.
fn presource_ref(i: PInput) -> PResult<(Token, Vec<PValue>)> {
    wsequence!(
        {
            name: pidentifier;
            params: opt(delimited_list("(", pvalue, ",", ")"));
        } => (name, params.unwrap_or_else(Vec::new))
    )(i)
}

/// A variable definition is a let var=value
#[derive(Debug, PartialEq)]
pub struct PVariableDef<'src> {
    pub metadata: Vec<PMetadata<'src>>,
    pub name: Token<'src>,
    pub value: PComplexValue<'src>,
}
fn pvariable_definition(i: PInput) -> PResult<PVariableDef> {
    wsequence!(
        {
            metadata: pmetadata_list;
            _let: estag("let");
            name: pidentifier;
            // TODO a type could be added here (but mostly useless since there is a value)
            _t: etag("=");
            value: or_fail(pcomplex_value, || PErrorKind::ExpectedKeyword("value"));
        } => PVariableDef { metadata, name, value }
    )(i)
}

/// A variable extension is a var=value without let, and no metadata
#[derive(Debug, PartialEq)]
pub struct PVariableExt<'src> {
    pub name: Token<'src>,
    pub value: PComplexValue<'src>,
}
fn pvariable_extension(i: PInput) -> PResult<PVariableExt> {
    wsequence!(
        {
            //metadata: pmetadata_list;
            name: pidentifier;
            _t: etag("=");
            value: or_fail(pcomplex_value, || PErrorKind::ExpectedKeyword("value"));
            //_fail: or_fail(verify(peek(anychar), |_| metadata.is_empty()), || PErrorKind::UnsupportedMetadata(metadata[0].source.into()));
        } => PVariableExt { name, value }
    )(i)
}

/// Global variable declaration is used both for declaration of normal variables and reserved agents variables
/// It is a let namespace.var:type
#[derive(Debug, PartialEq)]
pub struct PVariableDecl<'src> {
    pub metadata: Vec<PMetadata<'src>>,
    pub name: Token<'src>,
    pub sub_elts: Vec<Token<'src>>, // for struct items
    pub type_: Option<PType<'src>>,
}
fn pvariable_declaration(i: PInput) -> PResult<PVariableDecl> {
    wsequence!(
        {
            metadata: pmetadata_list;
            _identifier: estag("let");
            name: or_fail(pidentifier, || PErrorKind::ExpectedKeyword("namespace"));
            sub_elts: many0(preceded(sp(etag(".")), pidentifier));
            type_: opt(preceded(sp(etag(":")),ptype));
        } => PVariableDecl { metadata, name, sub_elts, type_ }
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
        value(PCallMode::Condition, etag("?")),
        value(PCallMode::Audit, etag("!")),
        value(PCallMode::Enforce, peek(anychar)),
    ))(i)
}

/// A State Declaration is a given required state on a given resource
#[derive(Debug, PartialEq)]
pub struct PStateDeclaration<'src> {
    pub source: Token<'src>,
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
            _t: etag(".");
            state: pidentifier;
            state_params: delimited_list("(", pvalue, ",", ")");
            outcome: opt(preceded(sp(etag("as")),pidentifier));
            :source(mode..)
        } => PStateDeclaration {
                source,
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
#[allow(clippy::large_enum_variant)]
pub enum PStatement<'src> {
    VariableDefinition(PVariableDef<'src>),
    VariableExtension(PVariableExt<'src>),
    StateDeclaration(PStateDeclaration<'src>),
    //   case keyword, list (condition   ,       then)
    Case(
        Token<'src>,
        Vec<(PEnumExpression<'src>, Vec<PStatement<'src>>)>,
    ), // keep the pinput since it will be reparsed later
    // Stop engine with a final message
    Fail(PValue<'src>),
    // Inform the user of something
    LogDebug(PValue<'src>),
    LogInfo(PValue<'src>),
    LogWarn(PValue<'src>),
    // Return a specific outcome
    Return(Token<'src>),
    // Do nothing
    Noop,
}
/// A single case in a case switch
fn pcase(i: PInput) -> PResult<(PEnumExpression, Vec<PStatement>)> {
    alt((
        map(etag("nodefault"), |t| {
            (
                PEnumExpression {
                    source: Token::from(t),
                    expression: PEnumExpressionPart::NoDefault(Token::from(t)),
                },
                vec![PStatement::Noop],
            )
        }),
        wsequence!(
            {
                expr: or_fail(penum_expression, || PErrorKind::ExpectedKeyword("enum expression"));
                _x: ftag("=>");
                stmt: or_fail(alt((
                    map(pstatement, |x| vec![x]),
                    delimited_parser("{", |j| many0(pstatement)(j), "}"),
                )), || PErrorKind::ExpectedToken("case statement"));
            } => (expr,stmt)
        ),
    ))(i)
}
fn pstatement(i: PInput) -> PResult<PStatement> {
    alt((
        // One state
        map(pstate_declaration, PStatement::StateDeclaration),
        // Variable definition
        map(pvariable_definition, PStatement::VariableDefinition),
        // Variable extension
        map(pvariable_extension, PStatement::VariableExtension),
        // case
        wsequence!(
            {
                metadata: pmetadata_list; // metadata is invalid here, check it after the 'case' tag below
                case: etag("case");
                _fail: or_fail(verify(peek(anychar), |_| metadata.is_empty()), || PErrorKind::UnsupportedMetadata(metadata[0].source.into()));
                cases: delimited_list("{", pcase, ",", "}" );
            } => PStatement::Case(case.into(), cases)
        ),
        // if
        wsequence!(
            {
                metadata: pmetadata_list; // metadata is invalid here, check it after the 'if' tag below
                case: estag("if");
                expr: or_fail(penum_expression, || PErrorKind::ExpectedKeyword("enum expression"));
                _x: ftag("=>");
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
                PStatement::Case(case.into(), vec![
                    ( expr, vec![statement] ),
                    ( PEnumExpression { source:"default".into(), expression: PEnumExpressionPart::Default("default".into()) },
                      vec![PStatement::Noop])
                ] )
            }
        ),
        // Flow statements
        map(
            preceded(sp(etag("return")), pvariable_identifier),
            PStatement::Return,
        ),
        map(preceded(sp(etag("fail")), pvalue), PStatement::Fail),
        map(
            preceded(sp(etag("log_debug")), pvalue),
            PStatement::LogDebug,
        ),
        map(preceded(sp(etag("log_info")), pvalue), PStatement::LogInfo),
        map(preceded(sp(etag("log_warn")), pvalue), PStatement::LogWarn),
        map(etag("noop"), |_| PStatement::Noop),
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
            _st: estag("state");
            name: pidentifier;
            param_list: delimited_list("(", pparameter, ",", ")");
            statements: delimited_parser("{", |j| many0(pstatement)(j),"}");
        } => {
            let (parameters, parameter_defaults) = param_list.into_iter().unzip();
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
            _x: estag("alias");
            resource_alias: pidentifier;
            resource_alias_parameters: delimited_list("(", pidentifier, ",", ")");
            _x: ftag(".");
            state_alias: pidentifier;
            state_alias_parameters: delimited_list("(", pidentifier, ",", ")");
            _x: ftag("=");
            resource: pidentifier;
            resource_parameters: delimited_list("(", pidentifier, ",", ")");
            _x: ftag(".");
            state: pidentifier;
            state_parameters: delimited_list("(", pidentifier, ",", ")");
        } => PAliasDef {metadata, resource_alias, resource_alias_parameters,
                        state_alias, state_alias_parameters,
                        resource, resource_parameters,
                        state, state_parameters }
    )(i)
}

/// A declaration is one of the a top level elements that can be found anywhere in the file.
#[derive(Debug, PartialEq)]
#[allow(clippy::large_enum_variant)]
pub enum PDeclaration<'src> {
    Enum(PEnum<'src>),
    SubEnum(PSubEnum<'src>),
    EnumAlias(PEnumAlias<'src>),
    Resource(
        (
            PResourceDef<'src>,
            Vec<Option<PValue<'src>>>,
            Option<Token<'src>>,
        ),
    ),
    State((PStateDef<'src>, Vec<Option<PValue<'src>>>)),
    GlobalVar(PVariableDef<'src>),
    GlobalVarExt(PVariableExt<'src>),
    MagicVar(PVariableDecl<'src>),
    Alias(PAliasDef<'src>),
}
fn pdeclaration(i: PInput) -> PResult<PDeclaration> {
    end_of_pfile(i)?;
    or_fail(
        // Note: most of this alt element start with a metadata
        // it would be more efficient to factor it out
        alt((
            map(penum_alias, PDeclaration::EnumAlias), // alias must come before enum since they start with the same tag
            map(penum, PDeclaration::Enum),
            map(psub_enum, PDeclaration::SubEnum),
            map(presource_def, PDeclaration::Resource),
            map(pstate_def, PDeclaration::State),
            map(pvariable_definition, PDeclaration::GlobalVar), // definition must come before declaration
            map(pvariable_declaration, PDeclaration::MagicVar),
            map(palias_def, PDeclaration::Alias),
            map(pvariable_extension, PDeclaration::GlobalVarExt), // extension should come last
        )),
        || PErrorKind::Unparsed(get_error_context(i, i)),
    )(i)
}

fn end_of_pfile(i: PInput) -> PResult<()> {
    let (i, _) = strip_spaces_and_comment(i)?;
    if i.fragment().is_empty() {
        return Err(nom::Err::Error(PError {
            context: None,
            kind: PErrorKind::Nom(VerboseError::from_error_kind(i, ErrorKind::Eof)),
        }));
    }
    Ok((i, ()))
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
