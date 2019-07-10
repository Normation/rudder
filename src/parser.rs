mod error;
mod token;

use nom::branch::*;
use nom::bytes::complete::*;
use nom::character::complete::*;
use nom::combinator::*;
use nom::multi::*;
use nom::number::complete::*;
use nom::sequence::*;
use nom::IResult;

use std::collections::HashMap;

use error::*;
use token::PInput;
// reexport tokens
pub use token::Token;


/// All structures are public to be read directly by other modules.
/// Parsing errors must be avoided if possible since they are fatal.
/// Keep the structure and handle the error in later analyser if possible.

// TODO add more types
// TODO more like var = f(x)
// TODO iterators
// TODO include
// TODO stateCall error management
// TODO resource parent
// TODO state alias
// TODO read json: read_untypedjson->json_obj and jsonobj.get(..)->result(value)
// TODO proptest

// ===== Public interfaces ===== 

/// The parse function that should be called when parsing a file
pub fn parse_file<'src>(
    filename: &'src str,
    content: &'src str,
) -> crate::error::Result<PFile<'src>> {
    fix_error_type(pfile(PInput::new_extra(filename, content)))
}

/// Parse a string for interpolation
pub fn parse_string(content: &str) -> crate::error::Result<Vec<PInterpolatedElement>> {
    fix_error_type(pinterpolated_string(PInput::new_extra("", content)))
}

// ===== Parsers =====

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
impl<'src> PComment<'src> {
    pub fn position(&self) -> Token<'src> {
        self.lines[0]
    }
}
impl<'src> ToString for PComment<'src> {
    fn to_string(&self) -> String {
        self.lines.iter().map(|x| x.to_string()).collect::<Vec<String>>().join("\n")
    }
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
fn penum_expression(i: PInput) -> Result<PEnumExpression> {
    alt((
        enum_or_expression,
        enum_and_expression,
        enum_not_expression,
        map(tag("default"), |t| PEnumExpression::Default(Token::from(t))), // default looks like an atom so it must come first
        enum_atom
    ))(i)
}
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
    Number,
    Boolean,
    Struct,
    List,
}
fn ptype(i: PInput) -> Result<PType> {
    alt((
        value(PType::String,  tag("string")),
        value(PType::Number,  tag("int")),
        value(PType::Boolean, tag("boolean")),
        value(PType::Struct,  tag("struct")),
        value(PType::List,    tag("list")),
    ))(i)
}

/// A number is currently represented by a float64
fn pnumber(i: PInput) -> Result<(Token, f64)> {
    let (i,val) = recognize_float(i)?;
    match double::<&[u8],(&[u8],nom::error::ErrorKind)>(val.fragment.as_bytes()) {
        Err(_) => panic!(format!("A parsed number canot be reparsed : {:?}", val)),
        Ok((_, n)) => Ok(( i, (val.into(),n) )),
    }
}

/// A list is stored in a Vec
fn plist(i: PInput) -> Result<Vec<PValue>> {
    wsequence!(
        {
            s: tag("[");
            values: separated_list(sp(tag(",")),pvalue);
            _x: or_fail(tag("]"),PErrorKind::UnterminatedDelimiter(s));
        } => values
    )(i)
}

/// A struct is stored in a HashMap
fn pstruct(i: PInput) -> Result<HashMap<String,PValue>> {
    wsequence!(
        {
            s: tag("{");
            values: separated_list(
                        sp(tag(",")),
                        separated_pair(pescaped_string, sp(tag(":")), pvalue)
                    );
            _x: or_fail(tag("}"),PErrorKind::UnterminatedDelimiter(s));
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
    Struct(HashMap<String,PValue<'src>>),
}
impl<'src> PValue<'src> {
    pub fn get_type(&self) -> PType {
        match self {
            PValue::String(_,_)       => PType::String,
            PValue::Number(_,_)       => PType::Number,
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
        map(pnumber,           |(x,y)| PValue::Number(x,y)),
        map(penum_expression,  |x|     PValue::EnumExpression(x)),
        map(plist,             |x|     PValue::List(x)),
        map(pstruct,           |x|     PValue::Struct(x)),
    ))(i)
}

/// A metadata is a key/value pair that gives properties to the statement that follows.
/// Currently metadata is not used by the compiler, just parsed, but that may change.
#[derive(Debug, PartialEq)]
pub struct PMetadata<'src> {
    pub key: Token<'src>,
    pub value: PValue<'src>,
}
fn pmetadata(i: PInput) -> Result<PMetadata> {
    wsequence!(
        {
            key: preceded(tag("@"), pidentifier);
            _x: or_fail(tag("="), PErrorKind::UnexpectedToken("="));
            value: pvalue;
        } => PMetadata { key, value }
    )(i)
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
fn pparameter(i: PInput) -> Result<(PParameter, Option<PValue>)> {
    wsequence!(
        {
            name: pidentifier;
            ptype: opt(
                    wsequence!(
                        {
                            _t: tag(":");
                            ty: or_fail(ptype,PErrorKind::ExpectedKeyword("type"));
                        } => ty)
                    );
            default: opt(
                    wsequence!(
                        {
                            _t: tag("=");
                            val: or_fail(pvalue,PErrorKind::ExpectedKeyword("value"));
                        } => val)
                    );
        } => (PParameter { ptype, name }, default)
    )(i)
}

/// A resource definition defines how a resource is uniquely identified.
#[derive(Debug, PartialEq)]
pub struct PResourceDef<'src> {
    pub name: Token<'src>,
    pub parameters: Vec<PParameter<'src>>,
    pub parameter_defaults: Vec<Option<PValue<'src>>>,
    pub parent: Option<Token<'src>>,
}
fn presource_def(i: PInput) -> Result<PResourceDef> {
    wsequence!(
        {
            _x: tag("resource");
            name: pidentifier;
            s: tag("(");
            parameter_list: separated_list(sp(tag(",")), pparameter);
            _x: or_fail(tag(")"), PErrorKind::UnterminatedDelimiter(s));
            parent: opt(preceded(sp(tag(":")),pidentifier));
        } => { 
            let (parameters, parameter_defaults) = parameter_list.into_iter().unzip();
            PResourceDef {
                      name,
                      parameters,
                      parameter_defaults,
                      parent,
            }
        }
    )(i)
}

/// A resource reference identifies a unique resource.
fn presource_ref(i: PInput) -> Result<(Token, Vec<PValue>)> {
    wsequence!(
        {
            name: pidentifier;
            params: opt(wsequence!(
                        {
                            t: tag("(");
                            parameters: separated_list(sp(tag(",")), pvalue);
                            _x: or_fail(tag(")"), PErrorKind::UnterminatedDelimiter(t));
                        } => parameters
                    ));
        } => (name, params.unwrap_or_else(Vec::new))
    )(i)
}

/// A variable definition is a var=value
fn pvariable_definition(i: PInput) -> Result<(Token, PValue)> {
    wsequence!(
        {
            variable: pidentifier;
            _t: tag("=");
            value: or_fail(pvalue, PErrorKind::ExpectedKeyword("value"));
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
fn pcall_mode(i: PInput) -> Result<PCallMode> {
    alt((
        value(PCallMode::Condition, tag("?")),
        value(PCallMode::Audit,     tag("!")),
        value(PCallMode::Enforce,   peek(anychar)),
    ))(i)
}

/// A statement is the atomic element of a state definition.
#[derive(Debug, PartialEq)]
pub enum PStatement<'src> {
    Comment(PComment<'src>),
    VariableDefinition(Token<'src>, PValue<'src>),
    StateCall(
        PCallMode,           // mode
        Token<'src>,         // resource
        Vec<PValue<'src>>,   // resource parameters
        Token<'src>,         // state name
        Vec<PValue<'src>>,   // parameters
        Option<Token<'src>>, // outcome
    ),
    //   case keyword, list (condition   ,       then)
    Case(Token<'src>, Vec<(PEnumExpression<'src>, Vec<PStatement<'src>>)>), // keep the pinput since it will be reparsed later
    // Stop engine with a final message
    Fail(PValue<'src>),
    // Inform the user of something
    Log(PValue<'src>),
    // Return a specific outcome
    Return(Token<'src>),
    // Do nothing
    Noop,
}
fn pstatement(i: PInput) -> Result<PStatement> {
    alt((
        // One state
        wsequence!(
            {
                mode: pcall_mode;
                resource: presource_ref;
                _t: tag(".");
                state: pidentifier;
                s: tag("(");
                parameters: separated_list( sp(tag(",")), pvalue );
                _x: or_fail(tag(")"), PErrorKind::UnterminatedDelimiter(s));
                outcome: opt(preceded(sp(tag("as")),pidentifier));
            } => PStatement::StateCall(mode,resource.0,resource.1,state,parameters,outcome)
        ),
        // Variable definition
        map(pvariable_definition, |(variable,value)| PStatement::VariableDefinition(variable,value)),
        // Comments
        map(pcomment, |x| PStatement::Comment(x)),
        // case
        wsequence!(
            {
                case: tag("case");
                s: tag("{");
                cases: separated_list(sp(tag(",")),
                        wsequence!(
                            {
                                expr: or_fail(penum_expression, PErrorKind::ExpectedKeyword("enum expression"));
                                _x: or_fail(tag("=>"), PErrorKind::UnexpectedToken("=>"));
                                stmt: or_fail(alt((
                                    map(pstatement, |x| vec![x]),
                                    wsequence!(
                                        {
                                            s: tag("{");
                                            vec: many0(pstatement);
                                            _x: or_fail(tag("}"),PErrorKind::UnterminatedDelimiter(s));
                                        } => vec
                                    ),
                                )), PErrorKind::ExpectedKeyword("statement"));
                            } => (expr,stmt)
                        ));
                _x: opt(tag(","));
                _x: or_fail(tag("}"),PErrorKind::UnterminatedDelimiter(s));
            } => PStatement::Case(case.into(), cases)
        ),
        // if 
        wsequence!(
            {
                case: tag("if");
                expr: or_fail(penum_expression, PErrorKind::ExpectedKeyword("enum expression"));
                _x: or_fail(tag("=>"), PErrorKind::UnexpectedToken("=>"));
                stmt: or_fail(pstatement, PErrorKind::ExpectedKeyword("statement"));
            } => PStatement::Case(case.into(), vec![(expr,vec![stmt]), (PEnumExpression::Default("default".into()),vec![PStatement::Noop])] )
        ),
        // Flow statements
        map(preceded(sp(tag("return")),pidentifier), |x| PStatement::Return(x)),
        map(preceded(sp(tag("fail")),pvalue),        |x| PStatement::Fail(x)),
        map(preceded(sp(tag("log")),pvalue),         |x| PStatement::Log(x)),
        map(tag("noop"),                             |_| PStatement::Noop),
    ))(i)
}

/// A state definition defines a state of a resource.
/// It is composed of one or more statements.
#[derive(Debug, PartialEq)]
pub struct PStateDef<'src> {
    pub name: Token<'src>,
    pub resource_name: Token<'src>,
    pub parameters: Vec<PParameter<'src>>,
    pub parameter_defaults: Vec<Option<PValue<'src>>>,
    pub statements: Vec<PStatement<'src>>,
}
fn pstate_def(i: PInput) -> Result<PStateDef> {
    wsequence!(
        {
            resource_name: pidentifier;
            _st: tag("state");
            name: pidentifier;
            s: or_fail(tag("("), PErrorKind::UnexpectedToken("("));
            parameter_list: separated_list(sp(tag(",")), pparameter);
            _x: or_fail(tag(")"), PErrorKind::UnterminatedDelimiter(s));
            sb: or_fail(tag("{"), PErrorKind::UnexpectedToken("{"));
            statements: many0(pstatement);
            _x: or_fail(tag("}"), PErrorKind::UnterminatedDelimiter(sb));
        } => {
            let (parameters, parameter_defaults) = parameter_list.into_iter().unzip();
            PStateDef {
                name,
                resource_name,
                parameters,
                parameter_defaults,
                statements,
            }
        }
    )(i)
}

#[derive(Debug, PartialEq)]
pub struct PAliasDef<'src> {
    resource_alias: Token<'src>,
    resource_alias_parameters: Vec<Token<'src>>,
    state_alias: Token<'src>,
    state_alias_parameters: Vec<Token<'src>>,
    resource: Token<'src>,
    resource_parameters: Vec<Token<'src>>,
    state: Token<'src>,
    state_parameters: Vec<Token<'src>>,
}
fn palias_def(i: PInput) -> Result<PAliasDef> {
    wsequence!(
        {
            _x: tag("alias");
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
        } => PAliasDef {resource_alias, resource_alias_parameters,
                        state_alias, state_alias_parameters,
                        resource, resource_parameters,
                        state, state_parameters }
    )(i)
}

/// A declaration is one of the a top level elements that can be found anywhere in the file.
#[derive(Debug, PartialEq)]
pub enum PDeclaration<'src> {
    Comment(PComment<'src>),
    Metadata(PMetadata<'src>),
    Resource(PResourceDef<'src>),
    State(PStateDef<'src>),
    Enum(PEnum<'src>),
    Mapping(PEnumMapping<'src>),
    GlobalVar(Token<'src>, PValue<'src>),
    Alias(PAliasDef<'src>),
}
fn pdeclaration(i: PInput) -> Result<PDeclaration> {
    alt((
        map(presource_def, |x| PDeclaration::Resource(x)),
        map(pmetadata, |x| PDeclaration::Metadata(x)),
        map(pstate_def, |x| PDeclaration::State(x)),
        map(pcomment, |x| PDeclaration::Comment(x)),
        map(penum, |x| PDeclaration::Enum(x)),
        map(penum_mapping, |x| PDeclaration::Mapping(x)),
        map(pvariable_definition, |(variable,value)| PDeclaration::GlobalVar(variable,value)),
        map(palias_def, |x| PDeclaration::Alias(x)),
    ))(i)
}

/// A PFile is the result of a single file parsing
/// It contains a valid header and top level declarations.
#[derive(Debug, PartialEq)]
pub struct PFile<'src> {
    pub header: PHeader,
    pub code: Vec<PDeclaration<'src>>,
}
fn pfile(i: PInput) -> Result<PFile> {
    all_consuming(sequence!(
        {
            header: pheader;
            code: many0(pdeclaration);
            _x: not(peek(anychar));
        } => PFile {header, code}
    ))(i)
}

// tests must be at the end to be able to test macros
#[cfg(test)]
mod tests;
