mod token;
mod macros;

use std::fmt;
use nom::*;
use enum_primitive::*;
use crate::error;
use self::macros::*;
use self::token::PInput;
pub use self::token::Token;
pub use self::token::pinput;

// TODO parse and store comments
// TODO add more types
// TODO lifetime = 'src
// TODO store position in strings
// TODO tests for high level parsers
// TODO _ in identifiers
// TODO reserve keywords

fn parser_err(context: &Context<PInput,u32>) -> error::Result<PFile<'static>>{
    match context {
        Context::Code(i,e) => {
            let (file,line,col) = Token::from(*i).position();
            match e {
                ErrorKind::Custom(err) => Err(error::Error::Parsing(format!("Error: {} at {}:{}:{}",PError::from_u32(*err).unwrap(),file,line,col),file,line,col)),
                e => Err(error::Error::Parsing(format!("Unprocessed parsing error '{:?}' {:?} at {}:{}:{}, please fill a BUG with context on when this happened",e,i, file,line,col), file,line,col)),
            }
        }
    }
}

pub fn parse_file<'a>(filename: &'a str, content: &'a str) -> error::Result<PFile<'a>> {
    match pfile(pinput(filename, content)) {
        Ok((_,file)) => Ok(file),
        Err(Err::Failure(context)) => parser_err(&context),
        Err(Err::Error(context)) => parser_err(&context),
        Err(Err::Incomplete(_)) => panic!("Incomplete should never happen"),
    }
}





/// All structures are public to be analysed by other modules


/// A source file header consists of a single line '@format=<version>'.
/// Shebang accepted.
#[derive(Debug, PartialEq)]
pub struct PHeader {
    pub version: u32,
}
pnamed!(header<PHeader>,
    do_parse!(
        opt!(preceded!(tag!("#!/"),take_until_and_consume!("\n"))) >>
        // strict parser so that this does not diverge and anything can read the line
        or_fail!(tag!("@format="),PError::InvalidFormat) >>
        version: or_fail!(map_res!(take_until!("\n"), |s:PInput| s.fragment.parse::<u32>()),PError::InvalidFormat) >>
        or_fail!(tag!("\n"),PError::InvalidFormat) >>
        (PHeader { version })
    )
);


/// A comment starts with a ## and ends with the end of line.
/// Such comment is parsed and kept contrarily to comment starting with '#'.
type PComment<'a> = Token<'a>;
pnamed!(pcomment<PComment>,
    map!(
        preceded!(tag!("##"),
            alt!(take_until_and_consume!("\n")
                |rest // This is in case we are at the end of the file without \n
            )
        ),
        |x| x.into()
    )
);

/// An identifier is a word that contains alphanumeric chars but does not start with a digit.
pub type PIdentifier<'a> = Token<'a>;
pnamed!(pub pidentifier<PIdentifier>,
    map!(
        // TODO accept _
        verify!(alphanumeric, |x:PInput| {
            // check first char
            let c=x.fragment.chars().next().unwrap_or(' '); // space is not a valid starting char so it can be used for None
            (c as u8 >= 0x41 && c as u8 <= 0x5A) || (c as u8 >= 0x61 && c as u8 <= 0x7A) || (c == '_')
        } ),
        |x| x.into()
    )
);

/// An enum is a list of values, like a C enum.
/// An enum can be global, which means its values are globally unique and can be guessed without specifying type.
/// A global enum also has a matching global variable with the same name as its type.
#[derive(Debug, PartialEq)]
pub struct PEnum<'a> {
    pub global: bool,
    pub name: PIdentifier<'a>,
    pub items: Vec<PIdentifier<'a>>,
}
pnamed!(pub penum<PEnum>,
    sp!(do_parse!(
        global: opt!(tag!("global")) >>
        tag!("enum") >>
        name: or_fail!(pidentifier,PError::InvalidName) >>
        tag!("{") >>
        items: sp!(separated_list!(tag!(","), pidentifier)) >>
        or_fail!(tag!("}"),PError::UnterminatedCurly) >>
        (PEnum {global: global.is_some(), name, items})
    ))
);

/// An enum mapping maps an enum to another one creating the second one in the process.
/// The mapping must map all items from the source enum.
/// A default keyword '*' can be used to map all unmapped items.
/// '*->xxx' maps then to xxx, '*->*' maps them to the same name in the new enum.
/// The new enum as the same properties as the original one .
#[derive(Debug, PartialEq)]
pub struct PEnumMapping<'a> {
    pub from: PIdentifier<'a>,
    pub to: PIdentifier<'a>,
    pub mapping: Vec<(PIdentifier<'a>, PIdentifier<'a>)>,
}
pnamed!(pub penum_mapping<PEnumMapping>,
    sp!(do_parse!(
        tag!("enum") >>
        from: or_fail!(pidentifier,PError::InvalidName) >>
        tag!("~>") >>
        to: or_fail!(pidentifier,PError::InvalidName) >>
        tag!("{") >>
        mapping: sp!(separated_list!(
                tag!(","),
                separated_pair!(
                    alt!(pidentifier|map!(tag!("*"),|x| x.into())),
                    tag!("->"),
                    alt!(pidentifier|map!(tag!("*"),|x| x.into())))
            )) >>
        or_fail!(tag!("}"),PError::UnterminatedCurly) >>
        (PEnumMapping {from, to, mapping})
    ))
);

/// An enum expression is used as a condition in a case expression.
/// This is a boolean expression based on enum comparison.
/// A comparison check if the variable is of the right type and contains
/// the provided item as a value, or an ancestor item if this is a mapped enum.
/// 'default' is a value that is equivalent of 'true'.
#[derive(Debug, PartialEq)]
pub enum PEnumExpression<'a> {
    //             variable                 enum              value/item
    Compare(Option<PIdentifier<'a>>, Option<PIdentifier<'a>>, PIdentifier<'a>),
    And(Box<PEnumExpression<'a>>, Box<PEnumExpression<'a>>),
    Or(Box<PEnumExpression<'a>>, Box<PEnumExpression<'a>>),
    Not(Box<PEnumExpression<'a>>),
    Default,
}
pnamed!(pub penum_expression<PEnumExpression>,
    // an expression must be exact (ie full string) as this parser can be used in isolation
    or_fail!(exact!(sub_enum_expression),PError::EnumExpression)
);
pnamed!(sub_enum_expression<PEnumExpression>,
    alt!(enum_or_expression
       | enum_and_expression
       | enum_not_expression
       | enum_atom
       | tag!("default") => { |_| PEnumExpression::Default }
    )
);
pnamed!(enum_atom<PEnumExpression>,
    sp!(alt!(
        delimited!(tag!("("),sub_enum_expression,tag!(")"))
      | do_parse!(
            var: pidentifier >>
            tag!("=~") >>
            penum: opt!(terminated!(pidentifier,tag!(":"))) >>
            value: pidentifier >>
            (PEnumExpression::Compare(Some(var),penum,value))
        )
      | do_parse!(
            var: pidentifier >>
            tag!("!~") >>
            penum: opt!(terminated!(pidentifier,tag!(":"))) >>
            value: pidentifier >>
            (PEnumExpression::Not(Box::new(PEnumExpression::Compare(Some(var),penum,value))))
        )
      | do_parse!(
            penum: opt!(terminated!(pidentifier,tag!(":"))) >>
            value: pidentifier >>
            (PEnumExpression::Compare(None,penum,value))
        )
    ))
);
pnamed!(enum_or_expression<PEnumExpression>,
    sp!(do_parse!(
        left: alt!(enum_and_expression | enum_not_expression | enum_atom) >>
        tag!("||") >>
        right: alt!(enum_or_expression | enum_and_expression | enum_not_expression | enum_atom) >>
        (PEnumExpression::Or(Box::new(left),Box::new(right)))
    ))
);
pnamed!(enum_and_expression<PEnumExpression>,
    sp!(do_parse!(
        left: alt!(enum_not_expression | enum_atom) >>
        tag!("&&") >>
        right: alt!(enum_and_expression | enum_not_expression | enum_atom) >>
        (PEnumExpression::And(Box::new(left),Box::new(right)))
    ))
);
pnamed!(enum_not_expression<PEnumExpression>,
    sp!(do_parse!(
        tag!("!") >>
        right: enum_atom >>
        (PEnumExpression::Not(Box::new(right)))
    ))
);

/// A PType is the type a variable or a parameter can take.
#[derive(Debug, PartialEq)]
pub enum PType {
    TString,
    TInteger,
    TStruct,
    TList,
}
pnamed!(ptype<PType>,
    alt!(
        tag!("string")      => { |_| PType::TString }  |
        tag!("int")         => { |_| PType::TInteger } |
        tag!("struct")      => { |_| PType::TStruct }  |
        tag!("list")        => { |_| PType::TList }
    )
);

/// An escaped string is a string delimited by '"' and that support backslash escapes.
pnamed!(escaped_string<String>,
    delimited!(
        tag!("\""),
        or_fail!(escaped_transform!(
            take_until_either1!("\\\""),
            '\\',
            alt!(
                tag!("\\") => { |_| "\\" } |
                tag!("\"") => { |_| "\"" } |
                tag!("n")  => { |_| "\n" } |
                tag!("r")  => { |_| "\r" } |
                tag!("t")  => { |_| "\t" }
            )
        ),PError::InvalidEscape),
        or_fail!(tag!("\""),PError::UnterminatedString)
    )
);

/// An unescaped string is a litteral string delimited by '"""'.
pnamed!(unescaped_string<String>,
    delimited!(
        // TODO string containing """ ?
        tag!("\"\"\""),
        map!(or_fail!(take_until!("\"\"\""),PError::UnterminatedString), { |x:PInput| x.to_string() }),
        tag!("\"\"\"")
    )
);

/// PValue is a typed value of the content of a variable or a parameter.
#[derive(Debug, PartialEq)]
pub enum PValue<'a> {
    String(String),
    // to make sure we have a reference in this struct because there will be one some day
    #[allow(dead_code)]
    XX(Token<'a>),
    //VInteger(u64),
    //...
}
pnamed!(pvalue<PValue>,
    // TODO other types
    alt!(
        unescaped_string  => { |x| PValue::String(x) }
      | escaped_string    => { |x| PValue::String(x) }
    )
);

/// A metadata is a key/value pair that gives properties to the statement that follows.
/// Currently metadata is not used by the compiler, just parsed, but that may change.
#[derive(Debug, PartialEq)]
pub struct PMetadata<'a> {
    pub key: PIdentifier<'a>,
    pub value: PValue<'a>,
}
pnamed!(pmetadata<PMetadata>,
    sp!(do_parse!(char!('@') >>
        key: pidentifier >>
        char!('=') >>
        value: pvalue >>
        (PMetadata {key, value})
    ))
);

/// A parameters defines how a parameter can be passed.
/// Its is of the form name:type=default where type and default are optional.
/// Type can be guessed from default.
#[derive(Debug, PartialEq)]
pub struct PParameter<'a> {
    pub name: PIdentifier<'a>,
    pub ptype: Option<PType>,
    pub default: Option<PValue<'a>>,
}
pnamed!(pparameter<PParameter>,
    sp!(do_parse!(
        ptype: opt!(sp!(terminated!(ptype, char!(':')))) >>
        name: pidentifier >>
        default: opt!(sp!(preceded!(tag!("="),pvalue))) >>
        (PParameter {ptype, name, default})
    ))
);

/// A resource definition defines how a resource is uniquely identified.
#[derive(Debug, PartialEq)]
pub struct PResourceDef<'a> {
    pub name: PIdentifier<'a>,
    pub parameters: Vec<PParameter<'a>>,
}
pnamed!(presource_def<PResourceDef>,
    sp!(do_parse!(
        tag!("resource") >>
        name: pidentifier >>
        tag!("(") >>
        parameters: separated_list!(
            tag!(","),
            pparameter) >>
        tag!(")") >>
        (PResourceDef {name, parameters})
    ))
);

/// A resource reference identifies a unique resource.
#[derive(Debug, PartialEq)]
pub struct PResourceRef<'a> {
    pub name: PIdentifier<'a>,
    pub parameters: Vec<PValue<'a>>,
}
pnamed!(presource_ref<PResourceRef>,
    sp!(do_parse!(
        name: pidentifier >>
        params: opt!(sp!(do_parse!(tag!("(") >>
            parameters: separated_list!(
                tag!(","),
                pvalue) >>
            tag!(")") >>
            (parameters) ))) >>
        (PResourceRef {name, parameters: params.unwrap_or(vec![])})
    ))
);

/// A call mode tell how a state must be applied
#[derive(Debug, PartialEq)]
pub enum PCallMode {
    Enforce,
    Condition,
    Audit,
}
pnamed!(pcall_mode<PCallMode>,
    sp!(alt!(
        tag!("?")                     => { |_| PCallMode::Condition } |
        tag!("!")                     => { |_| PCallMode::Audit }     |
        value!(PCallMode::Enforce)
    ))
);

/// A statement is the atomic element of a state definition.
#[derive(Debug, PartialEq)]
pub enum PStatement<'a> {
    Comment(PComment<'a>),
    StateCall(
        Option<PIdentifier<'a>>, // outcome
        PCallMode,          // mode
        PResourceRef<'a>,   // resource
        PIdentifier<'a>,         // state name
        Vec<PValue<'a>>,    // parameters
    ),
    //   list of condition          then
    Case(Vec<(PEnumExpression<'a>, Box<PStatement<'a>>)>),
    // Stop engine
    Fail(Token<'a>),
    // Inform the user of something
    Log(Token<'a>),
    // Return a specific outcome
    Return(PIdentifier<'a>),
    // Do nothing
    Noop,
    // TODO condition instance, resource instance, variable definition
}
pnamed!(pstatement<PStatement>,
    alt!(
        // state call
        sp!(do_parse!(
            outcome: opt!(terminated!(pidentifier,tag!("="))) >>
            mode: pcall_mode >>
            resource: presource_ref >>
            tag!(".") >>
            state: pidentifier >>
            tag!("(") >>
            parameters: separated_list!(
                tag!(","),
                pvalue) >>
            tag!(")") >>
            (PStatement::StateCall(outcome,mode,resource,state,parameters))
        ))
      | pcomment => { |x| PStatement::Comment(x) }
        // case
      | sp!(do_parse!(
            tag!("case") >>
            tag!("{") >>
            cases: separated_list!(tag!(","),
                      do_parse!(
                          // flatmap to take until '=>' then parse expression separately for better
                          // error management (penum_expression must not leave any unparsed token)
                          expr: flat_map!(take_until!("=>"),penum_expression) >>
                          tag!("=>") >>
                          stmt: pstatement >>
                          ((expr,Box::new(stmt)))
                  )) >>
            (PStatement::Case(cases))
        ))
        // if
      | sp!(do_parse!(
            tag!("if") >>
            // same comment as above
            expr: flat_map!(take_until!("=>"),penum_expression) >>
            tag!("=>") >>
            stmt: pstatement >>
            (PStatement::Case( vec![(expr,Box::new(stmt)), (PEnumExpression::Default,Box::new(PStatement::Log(Token::new("","TODO"))))] ))
        ))
        // Flow statements
      | tag!("fail!")    => { |_| PStatement::Fail(Token::new("","TODO")) } // TODO proper message
      | tag!("return!!") => { |_| PStatement::Return(Token::new("","TODO")) } // TODO proper message
      | tag!("log!")     => { |_| PStatement::Log(Token::new("","TODO")) } // TODO proper message
      | tag!("noop!")    => { |_| PStatement::Noop }
    )
);

/// A state definition defines a state of a resource.
/// It is composed of one or more statements.
#[derive(Debug, PartialEq)]
pub struct PStateDef<'a> {
    pub name: PIdentifier<'a>,
    pub resource_name: PIdentifier<'a>,
    pub parameters: Vec<PParameter<'a>>,
    pub statements: Vec<PStatement<'a>>,
}
pnamed!(pstate_def<PStateDef>,
    sp!(do_parse!(
        resource_name: pidentifier >>
        tag!("state") >>
        name: pidentifier >>
        tag!("(") >>
        parameters: separated_list!(
           tag!(","),
           pparameter) >>
       tag!(")") >>
       tag!("{") >>
       statements: many0!(pstatement) >>
       tag!("}") >>
       (PStateDef {name, resource_name, parameters, statements })
    ))
);

/// A declaration is one of the a top level elements that can be found anywhere in the file.
#[derive(Debug, PartialEq)]
pub enum PDeclaration<'a> {
    Comment(PComment<'a>),
    Metadata(PMetadata<'a>),
    Resource(PResourceDef<'a>),
    State(PStateDef<'a>),
    Enum(PEnum<'a>),
    Mapping(PEnumMapping<'a>),
}
pnamed!(pdeclaration<PDeclaration>,
    sp!(alt_complete!(
          presource_def    => { |x| PDeclaration::Resource(x) }
        | pmetadata        => { |x| PDeclaration::Metadata(x) }
        | pstate_def       => { |x| PDeclaration::State(x) }
        | pcomment         => { |x| PDeclaration::Comment(x) }
        | penum            => { |x| PDeclaration::Enum(x) }
        | penum_mapping    => { |x| PDeclaration::Mapping(x) }
      ))
);

/// A PFile is the result of a single file parsing
/// It contains a valid header and top level declarations.
#[derive(Debug, PartialEq)]
pub struct PFile<'a> {
    pub header: PHeader,
    pub code: Vec<PDeclaration<'a>>,
}
pnamed!(pub pfile<PFile>,
    sp!(do_parse!(
        header: header >>
        code: many0!(pdeclaration) >>
        eof!() >>
        (PFile {header, code} )
    ))
);

// enum_from primitive allows recreating PError from u32 easily (ie without writing tons of
// boilerplate) This would be useless if we had ErrorKind(PError) return codes but this
// would mean writing a lot of fix_error! calls in parsers
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

impl fmt::Display for PError {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        f.write_str(
            match self {
                PError::Unknown             => "Unknown error, this should not happen except in tests",
                PError::InvalidFormat       => "Invalid format",
                PError::UnterminatedString  => "Unterminated string",
                PError::InvalidEscape       => "Invalide escape character after \\ in string",
                PError::UnterminatedCurly   => "Unterminated curly brace",
                PError::InvalidName         => "Invalid identifier name",
                PError::EnumExpression      => "Invalid enum expression",
            }
        )
    }
}


// TESTS
//

#[cfg(test)]
mod tests {
    use super::*;

    // Adapter to simplify writing tests
    fn mapok<'a, O, E>(
        r: Result<(PInput<'a>, O), Err<PInput<'a>, E>>,
    ) -> Result<(&str, O), Err<PInput<'a>, E>> {
        match r {
            Ok((x, y)) => Ok((*x.fragment, y)),
            Err(e) => Err(e),
        }
    }

    // Adapter to simplify writing tests
    fn maperr<'a, O>(
        r: Result<(PInput<'a>, O), Err<PInput<'a>, u32>>,
    ) -> Result<(PInput<'a>, O), PError> {
        match r {
            Err(Err::Failure(Context::Code(_,ErrorKind::Custom(e)))) => Err(PError::from_u32(e).unwrap()),
            Err(Err::Error(Context::Code(_,ErrorKind::Custom(e)))) => Err(PError::from_u32(e).unwrap()),
            Err(Err::Incomplete(_)) => panic!("Incomplete should never happen"),
            Err(_) => Err(PError::Unknown),
            Ok((x,y)) => Ok((x,y)),
        }
    }

    #[test]
    fn test_strings() {
        assert_eq!(
            mapok(escaped_string(pinput("", "\"1hello\\n\\\"Herman\\\"\n\""))),
            Ok(("", "1hello\n\"Herman\"\n".to_string()))
        );
        assert_eq!(
            mapok(unescaped_string(pinput(
                "",
                "\"\"\"2hello\\n\"Herman\"\n\"\"\""
            ))),
            Ok(("", "2hello\\n\"Herman\"\n".to_string()))
        );
        assert!(escaped_string(pinput("", "\"2hello\\n\\\"Herman\\\"\n")).is_err());
    }

    #[test]
    fn test_enum() {
        assert_eq!(
            mapok(penum(pinput("", "enum abc { a, b, c }"))),
            Ok((
                "",
                PEnum {
                    global: false,
                    name: "abc".into(),
                    items: vec!["a".into(), "b".into(), "c".into()]
                }
            ))
        );
        assert_eq!(
            mapok(penum(pinput("", "global enum abc { a, b, c }"))),
            Ok((
                "",
                PEnum {
                    global: true,
                    name: "abc".into(),
                    items: vec!["a".into(), "b".into(), "c".into()]
                }
            ))
        );
        assert_eq!(
            mapok(penum_mapping(pinput(
                "",
                "enum abc ~> def { a -> d, b -> e, * -> f}"
            ))),
            Ok((
                "",
                PEnumMapping {
                    from: "abc".into(),
                    to: "def".into(),
                    mapping: vec![
                        ("a".into(), "d".into()),
                        ("b".into(), "e".into()),
                        ("*".into(), "f".into()),
                    ]
                }
            ))
        );
        assert_eq!(
            mapok(penum_mapping(pinput(
                "",
                //"enum outcome~>okerr{kept->ok,repaired->ok,error->error}"
                "enum outcome ~> okerr { kept->ok, repaired->ok, error->error }",
            ))),
            Ok((
                "",
                PEnumMapping {
                    from: "outcome".into(),
                    to: "okerr".into(),
                    mapping: vec![
                        ("kept".into(), "ok".into()),
                        ("repaired".into(), "ok".into()),
                        ("error".into(), "error".into()),
                    ]
                }
            ))
        );
    }

    #[test]
    fn test_penum_expression() {
        assert_eq!(
            mapok(penum_expression(pinput("", "a=~b:c"))),
            Ok((
                "",
                PEnumExpression::Compare(Some("a".into()), Some("b".into()), "c".into())
            ))
        );
        assert_eq!(
            mapok(penum_expression(pinput("", "a=~bc"))),
            Ok((
                "",
                PEnumExpression::Compare(Some("a".into()), None, "bc".into())
            ))
        );
        assert_eq!(
            mapok(penum_expression(pinput("", "bc"))),
            Ok(("", PEnumExpression::Compare(None, None, "bc".into())))
        );
        assert_eq!(
            mapok(penum_expression(pinput("", "(a =~ b:hello)"))),
            Ok((
                "",
                PEnumExpression::Compare(Some("a".into()), Some("b".into()), "hello".into())
            ))
        );
        assert_eq!(
            mapok(penum_expression(pinput("", "(a !~ b:hello)"))),
            Ok((
                "",
                PEnumExpression::Not(Box::new(PEnumExpression::Compare(
                    Some("a".into()),
                    Some("b".into()),
                    "hello".into()
                )))
            ))
        );
        assert_eq!(
            mapok(penum_expression(pinput("", "bc&&(a||b=~hello:g)"))),
            Ok((
                "",
                PEnumExpression::And(
                    Box::new(PEnumExpression::Compare(None, None, "bc".into())),
                    Box::new(PEnumExpression::Or(
                        Box::new(PEnumExpression::Compare(None, None, "a".into())),
                        Box::new(PEnumExpression::Compare(
                            Some("b".into()),
                            Some("hello".into()),
                            "g".into()
                        ))
                    )),
                )
            ))
        );
        assert_eq!(
            mapok(penum_expression(pinput("", "! a =~ hello"))),
            Ok((
                "",
                PEnumExpression::Not(Box::new(PEnumExpression::Compare(
                    Some("a".into()),
                    None,
                    "hello".into()
                )))
            ))
        );
    }

    #[test]
    fn test_comment_out() {
          assert_eq!(
              mapok(penum(pinput("", "enum abc#comment\n { a, b, c }"))),
              mapok(penum(pinput("", "enum abc { a, b, c }")))
          );
          assert_eq!(
              mapok(penum(pinput("", "enum abc#\n { a, b, c }"))),
              mapok(penum(pinput("", "enum abc { a, b, c }")))
          );
    }

    #[test]
    fn test_pcomment() {
        assert_eq!(
            mapok(pcomment(pinput("", "##hello Herman\n"))),
            Ok(("", "hello Herman".into()))
        );
        assert_eq!(
            mapok(pcomment(pinput("", "##hello Herman\nHola"))),
            Ok(("Hola", "hello Herman".into()))
        );
        assert_eq!(
            mapok(pcomment(pinput("", "##hello Herman!"))),
            Ok(("", "hello Herman!".into()))
        );
        assert_eq!(
            mapok(pcomment(pinput("", "##hello\nHerman\n"))),
            Ok(("Herman\n", "hello".into()))
        );
        assert!(pcomment(pinput("", "hello\nHerman\n")).is_err());
    }

    #[test]
    fn test_pmetadata() {
        assert_eq!(
            mapok(pmetadata(pinput("", "@key=\"value\""))),
            Ok((
                "",
                PMetadata {
                    key: "key".into(),
                    value: PValue::String("value".to_string())
                }
            ))
        );
        assert_eq!(
            mapok(pmetadata(pinput("", "@key = \"value\""))),
            Ok((
                "",
                PMetadata {
                    key: "key".into(),
                    value: PValue::String("value".to_string())
                }
            ))
        );
    }

    #[test]
    fn test_pidentifier() {
        assert_eq!(
            mapok(pidentifier(pinput("", "simple "))),
            Ok((" ", "simple".into()))
        );
        assert_eq!(
            mapok(pidentifier(pinput("", "simple?"))),
            Ok(("?", "simple".into()))
        );
        assert_eq!(
            mapok(pidentifier(pinput("", "simpl3 "))),
            Ok((" ", "simpl3".into()))
        );
        // TODO accept _
        //        assert_eq!(
        //            mapok(pidentifier(pinput("", "simple_word "))),
        //            Ok((" ", "simple_word".into()))
        //        );
        assert!(pidentifier(pinput("", "%imple ")).is_err());
        assert!(pidentifier(pinput("", "5imple ")).is_err());
    }

    #[test]
    fn test_pparameter() {
        assert_eq!(
            mapok(pparameter(pinput("", "hello "))),
            Ok((
                "",
                PParameter {
                    name: "hello".into(),
                    ptype: None,
                    default: None,
                }
            ))
        );
        assert_eq!(
            mapok(pparameter(pinput("", "string:hello "))),
            Ok((
                "",
                PParameter {
                    name: "hello".into(),
                    ptype: Some(PType::TString),
                    default: None,
                }
            ))
        );
        assert_eq!(
            mapok(pparameter(pinput("", " string : hello "))),
            Ok((
                "",
                PParameter {
                    name: "hello".into(),
                    ptype: Some(PType::TString),
                    default: None,
                }
            ))
        );
        assert_eq!(
            mapok(pparameter(pinput("", " string : hello=\"default\""))),
            Ok((
                "",
                PParameter {
                    name: "hello".into(),
                    ptype: Some(PType::TString),
                    default: Some(PValue::String("default".to_string())),
                }
            ))
        );
    }

    #[test]
    fn test_presource_def() {
        assert_eq!(
            mapok(presource_def(pinput("", "resource hello()"))),
            Ok((
                "",
                PResourceDef {
                    name: "hello".into(),
                    parameters: vec![]
                }
            ))
        );
        assert_eq!(
            mapok(presource_def(pinput("", "resource  hello2 ( )"))),
            Ok((
                "",
                PResourceDef {
                    name: "hello2".into(),
                    parameters: vec![]
                }
            ))
        );
        assert_eq!(
            mapok(presource_def(pinput("", "resource hello (string: p1, p2)"))),
            Ok((
                "",
                PResourceDef {
                    name: "hello".into(),
                    parameters: vec![
                        PParameter {
                            name: "p1".into(),
                            ptype: Some(PType::TString),
                            default: None,
                        },
                        PParameter {
                            name: "p2".into(),
                            ptype: None,
                            default: None,
                        }
                    ]
                }
            ))
        );
    }

    #[test]
    fn test_value() {
        // TODO other types
        assert_eq!(
            mapok(pvalue(pinput("", "\"\"\"This is a string\"\"\""))),
            Ok(("", PValue::String("This is a string".to_string())))
        );
        assert_eq!(
            mapok(pvalue(pinput("", "\"This is a string bis\""))),
            Ok(("", PValue::String("This is a string bis".to_string())))
        );
    }

    #[test]
    fn test_headers() {
        assert_eq!(
            mapok(header(pinput("", "#!/bin/bash\n@format=1\n"))),
            Ok(("", PHeader { version: 1 }))
        );
        assert_eq!(
            mapok(header(pinput("", "@format=21\n"))),
            Ok(("", PHeader { version: 21 }))
        );
        assert!(header(pinput("", "@format=21.5\n")).is_err());
    }

    #[test]
    fn test_presource_ref() {
        assert_eq!(
            mapok(presource_ref(pinput("", "hello()"))),
            Ok((
                "",
                PResourceRef {
                    name: "hello".into(),
                    parameters: vec![]
                }
            ))
        );
        assert_eq!(
            mapok(presource_ref(pinput("", "hello3 "))),
            Ok((
                "",
                PResourceRef {
                    name: "hello3".into(),
                    parameters: vec![]
                }
            ))
        );
        assert_eq!(
            mapok(presource_ref(pinput("", "hello ( \"p1\", \"p2\" )"))),
            Ok((
                "",
                PResourceRef {
                    name: "hello".into(),
                    parameters: vec![
                        PValue::String("p1".to_string()),
                        PValue::String("p2".to_string())
                    ]
                }
            ))
        );
        assert_eq!(
            mapok(presource_ref(pinput("", "hello2 ( )"))),
            Ok((
                "",
                PResourceRef {
                    name: "hello2".into(),
                    parameters: vec![]
                }
            ))
        );
    }

    #[test]
    fn test_pstatement() {
        assert_eq!(
            mapok(pstatement(pinput("", "resource().state()"))),
            Ok((
                "",
                PStatement::StateCall(
                    None,
                    PCallMode::Enforce,
                    PResourceRef {
                        name: "resource".into(),
                        parameters: vec![]
                    },
                    "state".into(),
                    vec![]
                )
            ))
        );
        assert_eq!(
            mapok(pstatement(pinput("", "resource().state( \"p1\", \"p2\")"))),
            Ok((
                "",
                PStatement::StateCall(
                    None,
                    PCallMode::Enforce,
                    PResourceRef {
                        name: "resource".into(),
                        parameters: vec![]
                    },
                    "state".into(),
                    vec![
                        PValue::String("p1".to_string()),
                        PValue::String("p2".to_string())
                    ]
                )
            ))
        );
        assert_eq!(
            mapok(pstatement(pinput("", "##hello Herman\n"))),
            Ok(("", PStatement::Comment("hello Herman".into())))
        );
    }

    #[test]
    fn test_pdeclaration() {
        assert_eq!(
            mapok(pdeclaration(pinput("","ntp state configuration ()\n{\n  file(\"/tmp\").permissions(\"root\", \"root\", \"g+w\")\n}\n"))),
            Ok(("\n",
                PDeclaration::State(PStateDef {
                    name: "configuration".into(),
                    resource_name: "ntp".into(),
                    parameters: vec![],
                    statements: vec![
                        PStatement::StateCall(
                            None, PCallMode::Enforce,
                            PResourceRef {
                                name: "file".into(), 
                                parameters: vec![PValue::String("/tmp".to_string())] }, 
                            "permissions".into(),
                            vec![PValue::String("root".to_string()), PValue::String("root".to_string()), PValue::String("g+w".to_string())]
                        )
                    ]
                })
            )));
    }

    #[test]
    fn test_state() {
        assert_eq!(
            mapok(pstate_def(pinput("", "resource state configuration() {}"))),
            Ok((
                "",
                PStateDef {
                    name: "configuration".into(),
                    resource_name: "resource".into(),
                    parameters: vec![],
                    statements: vec![]
                }
            ))
        );
    }

    #[test]
    fn test_errors() {
        assert_eq!(maperr(header(pinput("", "@format=21.5\n"))),Err(PError::InvalidFormat));
        assert_eq!(maperr(escaped_string(pinput("", "\"2hello"))),Err(PError::UnterminatedString));
        assert_eq!(maperr(unescaped_string(pinput("", "\"\"\"hello\"\""))),Err(PError::UnterminatedString));
        assert_eq!(maperr(pvalue(pinput("", "\"\"\"hello\"\""))),Err(PError::UnterminatedString));
        assert_eq!(maperr(pvalue(pinput("", "\"hello\\x\""))),Err(PError::InvalidEscape));
        assert_eq!(maperr(pvalue(pinput("", "\"hello\\"))),Err(PError::InvalidEscape));
        assert_eq!(maperr(penum(pinput("", "enum 2abc { a, b, c }"))),Err(PError::InvalidName));
        assert_eq!(maperr(penum(pinput("", "enum abc { a, b, }"))),Err(PError::UnterminatedCurly));
        assert_eq!(maperr(penum(pinput("", "enum abc { a, b"))),Err(PError::UnterminatedCurly));
        assert_eq!(maperr(penum_mapping(pinput("", "enum abc ~> { a -> b"))),Err(PError::InvalidName));
        assert_eq!(maperr(penum_mapping(pinput("", "enum abc ~> def { a -> b"))),Err(PError::UnterminatedCurly));
        assert_eq!(maperr(penum_expression(pinput("", "a=~b:"))),Err(PError::EnumExpression));
        assert_eq!(maperr(penum_expression(pinput("", "a=~"))),Err(PError::EnumExpression));
    }
}
