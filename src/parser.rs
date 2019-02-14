mod error;
mod macros;
mod token;

use self::error::{fix_error_type, PError};
use self::macros::*;
pub use self::token::pinput;
use self::token::PInput;
pub use self::token::Token;
use enum_primitive::*;
use nom::*;

/// All structures are public to be read directly by other modules.
/// Parsing errors must be avoided if possible since they are fatal.
/// Keep the structure and handle the error in later analyser if possible.

// TODO parse and store comments
// TODO add more types
// TODO lifetime = 'src
// TODO namespace in identifiers
// TODO iterators
// TODO trailing comma
// TODO move expression parsing out to enable multiple error reports

/// The parse function that should be called when parsing a file
pub fn parse_file<'a>(filename: &'a str, content: &'a str) -> crate::error::Result<PFile<'a>> {
    fix_error_type(pfile(pinput(filename, content)))
}

/// Parse a string for interpolation
pub fn parse_string(content: &str) -> crate::error::Result<(Vec<String>, Vec<String>)> {
    fix_error_type(interpolated_string(pinput("", content)))
}

/// Parse an enum expression
pub fn parse_enum_expression(content: PInput) -> crate::error::Result<(PEnumExpression)> {
    fix_error_type(penum_expression(content))
}

/// A source file header consists of a single line '@format=<version>'.
/// Shebang accepted.
#[derive(Debug, PartialEq)]
pub struct PHeader {
    pub version: u32,
}
pnamed!(
    header<PHeader>,
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
pub type PComment<'a> = Token<'a>;
pnamed!(
    pcomment<PComment>,
    map!(
        preceded!(
            tag!("##"),
            alt!(
                take_until_and_consume!("\n") | rest // This is in case we are at the end of the file without \n
            )
        ),
        |x| x.into()
    )
);

/// An identifier is a word that contains alphanumeric chars.
/// Be liberal here, they are checked again later
/// TODO should we accept other chars ?
/// TODO any reason an identifier should not start with a digit
pnamed!(pub pidentifier<Token>,
    map!(
        verify!(alphanumeric, |x:PInput| {
            // check first char
            let c=x.fragment.chars().next().unwrap_or(' '); // space is not a valid starting char so it can be used to mean None
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
    pub name: Token<'a>,
    pub items: Vec<Token<'a>>,
}
pnamed!(pub penum<PEnum>,
    sp!(do_parse!(
        global: opt!(tag!("global")) >>
        tag!("enum") >>
        name: or_fail!(pidentifier,PError::InvalidName) >>
        or_fail!(tag!("{"),PError::InvalidSeparator) >>
        items: sp!(separated_list!(tag!(","), pidentifier)) >>
        or_fail!(tag!("}"),PError::UnterminatedDelimiter) >>
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
    pub from: Token<'a>,
    pub to: Token<'a>,
    pub mapping: Vec<(Token<'a>, Token<'a>)>,
}
pnamed!(pub penum_mapping<PEnumMapping>,
    sp!(do_parse!(
        tag!("enum") >>
        from: or_fail!(pidentifier,PError::InvalidName) >>
        or_fail!(tag!("~>"),PError::InvalidSeparator) >>
        to: or_fail!(pidentifier,PError::InvalidName) >>
        or_fail!(tag!("{"),PError::InvalidSeparator) >>
        mapping: sp!(separated_list!(
                tag!(","),
                separated_pair!(
                    or_fail!(alt!(pidentifier|map!(tag!("*"),|x| x.into())),PError::InvalidName),
                    or_fail!(tag!("->"),PError::InvalidSeparator),
                    or_fail!(alt!(pidentifier|map!(tag!("*"),|x| x.into())),PError::InvalidName))
            )) >>
        or_fail!(tag!("}"),PError::UnterminatedDelimiter) >>
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
    Compare(Option<Token<'a>>, Option<Token<'a>>, Token<'a>),
    And(Box<PEnumExpression<'a>>, Box<PEnumExpression<'a>>),
    Or(Box<PEnumExpression<'a>>, Box<PEnumExpression<'a>>),
    Not(Box<PEnumExpression<'a>>),
    Default(Token<'a>),
}
pnamed!(pub penum_expression<PEnumExpression>,
    // an expression must be exact (ie full string) as this parser can be used in isolation
    or_fail!(exact!(
        do_parse!(s: sub_enum_expression >> space_s >> (s))
    ),PError::EnumExpression)
);
pnamed!(
    sub_enum_expression<PEnumExpression>,
    sp!(alt!(enum_or_expression
       | enum_and_expression
       | enum_not_expression
       | tag!("default") => { |t| PEnumExpression::Default(Token::from(t)) } // default looks like an atom so it comes first
       | enum_atom
    ))
);
pnamed!(
    enum_atom<PEnumExpression>,
    sp!(alt!(
        delimited!(tag!("("), sub_enum_expression, or_fail!(tag!(")"),PError::UnterminatedDelimiter))
            | do_parse!(
                var: pidentifier
                    >> tag!("=~")
                    >> penum: opt!(terminated!(pidentifier, tag!(":")))
                    >> value: pidentifier
                    >> (PEnumExpression::Compare(Some(var), penum, value))
            )
            | do_parse!(
                var: pidentifier
                    >> tag!("!~")
                    >> penum: opt!(terminated!(pidentifier, tag!(":")))
                    >> value: pidentifier
                    >> (PEnumExpression::Not(Box::new(PEnumExpression::Compare(
                        Some(var),
                        penum,
                        value
                    ))))
            )
            | do_parse!(
                penum: opt!(terminated!(pidentifier, tag!(":")))
                    >> value: pidentifier
                    >> (PEnumExpression::Compare(None, penum, value))
            )
    ))
);
pnamed!(
    enum_or_expression<PEnumExpression>,
    sp!(do_parse!(
        left: alt!(enum_and_expression | enum_not_expression | enum_atom)
            >> tag!("||")
            >> right:
                alt!(enum_or_expression | enum_and_expression | enum_not_expression | enum_atom)
            >> (PEnumExpression::Or(Box::new(left), Box::new(right)))
    ))
);
pnamed!(
    enum_and_expression<PEnumExpression>,
    sp!(do_parse!(
        left: alt!(enum_not_expression | enum_atom)
            >> tag!("&&")
            >> right: alt!(enum_and_expression | enum_not_expression | enum_atom)
            >> (PEnumExpression::And(Box::new(left), Box::new(right)))
    ))
);
pnamed!(
    enum_not_expression<PEnumExpression>,
    sp!(do_parse!(
        tag!("!") >> right: enum_atom >> (PEnumExpression::Not(Box::new(right)))
    ))
);

/// A PType is the type a variable or a parameter can take.
#[derive(Debug, PartialEq, Clone, Copy)]
pub enum PType {
    TString,
    TInteger,
    TStruct,
    TList,
}
pnamed!(
    ptype<PType>,
    alt!(
        tag!("string")      => { |_| PType::TString }  |
        tag!("int")         => { |_| PType::TInteger } |
        tag!("struct")      => { |_| PType::TStruct }  |
        tag!("list")        => { |_| PType::TList }
    )
);

/// An escaped string is a string delimited by '"' and that support backslash escapes.
pnamed!(
    //              opener unescaped str
    escaped_string<(Token, String)>,
    do_parse!(
        prefix: tag!("\"")
            >> content:
                or_fail!(
                    escaped_transform!(
                        take_until_either1!("\\\""),
                        '\\',
                        alt!(
                            tag!("\\") => { |_| "\\" } |
                            tag!("\"") => { |_| "\"" } |
                            tag!("n")  => { |_| "\n" } |
                            tag!("r")  => { |_| "\r" } |
                            tag!("t")  => { |_| "\t" }
                        )
                    ),
                    PError::InvalidEscape
                )
            >> or_fail!(tag!("\""), PError::UnterminatedString)
            >> ((prefix.into(), content))
    )
);

/// An unescaped string is a literal string delimited by '"""'.
pnamed!(
    //                opener unescaped str
    unescaped_string<(Token, String)>,
    do_parse!(
        prefix: tag!("\"\"\"")
            >> content:
                map!(
                    or_fail!(take_until!("\"\"\""), PError::UnterminatedString),
                    { |x: PInput| x.to_string() }
                )
            >> tag!("\"\"\"")
            >> ((prefix.into(), content))
    )
);

/// All strings are interpolated
pnamed!(
    interpolated_string<(Vec<String>,Vec<String>)>,
    exact!(do_parse!(
        sections: many0!(do_parse!(
            prefix: take_until!("$") >>
            tag!("$") >>
            variable: alt!(
                tag!("$") => {|_| None}
              | delimited!(tag!("{"),pidentifier,or_fail!(tag!("}"),PError::UnterminatedDelimiter)) => {|x| Some(x)}
            ) >>
            (prefix,variable)
        )) >>
        rest: rest >>
        ( {
            let mut format_vec = Vec::new();
            let mut var_vec = Vec::new();
            let mut current = String::new();
            sections.into_iter().for_each(|(s,v)| {
                match v {
                    None => {
                        current.push_str(*s.fragment);
                        current.push_str("$");
                    },
                    Some(var) => {
                        current.push_str(*s.fragment);
                        format_vec.push(current.clone()); // TODO clone is easy but should not be necessary
                        current = String::new();
                        var_vec.push(var.fragment().into());
                    },
                }
            });
            current.push_str(*rest.fragment);
            format_vec.push(current);
            (format_vec, var_vec)
        } )
    ))
);

/// PValue is a typed value of the content of a variable or a parameter.
#[derive(Debug, PartialEq)]
pub enum PValue<'a> {
    //     position   value
    String(Token<'a>, String),
}

pnamed!(
    pvalue<PValue>,
    // TODO other types
    alt!(
        unescaped_string  => { |(x,y)| PValue::String(x,y) }
      | escaped_string    => { |(x,y)| PValue::String(x,y) }
    )
);

/// A metadata is a key/value pair that gives properties to the statement that follows.
/// Currently metadata is not used by the compiler, just parsed, but that may change.
#[derive(Debug, PartialEq)]
pub struct PMetadata<'a> {
    pub key: Token<'a>,
    pub value: PValue<'a>,
}
pnamed!(
    pmetadata<PMetadata>,
    sp!(do_parse!(
        char!('@') >> key: pidentifier >> or_fail!(char!('='),PError::InvalidSeparator) >> value: pvalue >> (PMetadata { key, value })
    ))
);

/// A parameters defines how a parameter can be passed.
/// Its is of the form name:type=default where type and default are optional.
/// Type can be guessed from default.
#[derive(Debug, PartialEq)]
pub struct PParameter<'a> {
    pub name: Token<'a>,
    pub ptype: Option<PType>,
    pub default: Option<PValue<'a>>,
}
pnamed!(
    pparameter<PParameter>,
    sp!(do_parse!(
        ptype: opt!(sp!(terminated!(ptype, char!(':'))))
            >> name: pidentifier
            >> default: opt!(sp!(preceded!(tag!("="), pvalue)))
            >> (PParameter {
                ptype,
                name,
                default
            })
    ))
);

/// A resource definition defines how a resource is uniquely identified.
#[derive(Debug, PartialEq)]
pub struct PResourceDef<'a> {
    pub name: Token<'a>,
    pub parameters: Vec<PParameter<'a>>,
}
pnamed!(
    presource_def<PResourceDef>,
    sp!(do_parse!(
        tag!("resource")
            >> name: pidentifier
            >> tag!("(")
            >> parameters: separated_list!(tag!(","), pparameter)
            >> or_fail!(tag!(")"),PError::UnterminatedDelimiter)
            >> (PResourceDef { name, parameters })
    ))
);

/// A resource reference identifies a unique resource.
pnamed!(
    presource_ref<(Token,Vec<PValue>)>,
    sp!(do_parse!(
        name: pidentifier
            >> params:
                opt!(sp!(do_parse!(
                    tag!("(")
                        >> parameters: separated_list!(tag!(","), pvalue)
                        >> or_fail!(tag!(")"),PError::UnterminatedDelimiter)
                        >> (parameters)
                )))
            >> ((name, params.unwrap_or_else(Vec::new)))
    ))
);

/// A call mode tell how a state must be applied
#[derive(Debug, PartialEq)]
pub enum PCallMode {
    Enforce,
    Condition,
    Audit,
}
pnamed!(
    pcall_mode<PCallMode>,
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
    VariableDefinition(Token<'a>, PValue<'a>), // TODO function call et al. (with default)
    StateCall(
        PCallMode,         // mode
        Token<'a>,         // resource
        Vec<PValue<'a>>,   // resource parameters
        Token<'a>,         // state name
        Vec<PValue<'a>>,   // parameters
        Option<Token<'a>>, // outcome
                           // TODO Option<PStatement<'a>>, // error management
    ),
    //   case keyword, list (condition   ,       then)
    Case(Token<'a>, Vec<(PInput<'a>, Vec<PStatement<'a>>)>), // keep the pinput since it will be reparsed later
    // Stop engine
    Fail(Token<'a>),
    // Inform the user of something
    Log(Token<'a>),
    // Return a specific outcome
    Return(Token<'a>),
    // Do nothing
    Noop,
    // TODO condition instance, resource instance, variable definition
}
pnamed!(
    pstatement<PStatement>,
    alt!(
        // state call
        sp!(do_parse!(
            mode: pcall_mode >>
            resource: presource_ref >>
            tag!(".") >>
            state: pidentifier >>
            tag!("(") >>
            parameters: separated_list!(
                tag!(","),
                pvalue) >>
            or_fail!(tag!(")"),PError::UnterminatedDelimiter) >>
            outcome: opt!(sp!(preceded!(tag!("as"),pidentifier))) >>
            (PStatement::StateCall(mode,resource.0,resource.1,state,parameters,outcome))
        ))
      | sp!(do_parse!(
            variable: pidentifier >>
            tag!("=") >>
            value: pvalue >>
            (PStatement::VariableDefinition(variable,value))
        ))
      | pcomment => { |x| PStatement::Comment(x) }
        // case
      | sp!(do_parse!(
            case: tag!("case") >>
            tag!("{") >>
            cases: separated_list!(tag!(","),
                      do_parse!(
                          // only to take until '=>' to parse expression later for better
                          // error management (penum_expression must not leave any unparsed token)
                          expr: take_until!("=>") >>
                          tag!("=>") >>
                          stmt: alt!(
                              pstatement => { |x| vec![x] } |
                              do_parse!(
                                  tag!("{") >>
                                  vec: many0!(pstatement) >>
                                  or_fail!(tag!("}"),PError::UnterminatedDelimiter) >>
                                  (vec)
                              )
                          ) >>
                          ((expr,stmt))
                  )) >>
            or_fail!(tag!("}"),PError::UnterminatedDelimiter) >>
            (PStatement::Case(case.into(), cases))
        ))
        // if
      | sp!(do_parse!(
            case: tag!("if") >>
            // same comment as above
            expr: take_until!("=>") >>
            tag!("=>") >>
            stmt: pstatement >>
            (PStatement::Case(case.into(), vec![(expr.into(),vec![stmt]), (pinput(expr.extra,"default"),vec![PStatement::Log(Token::new("","TODO"))])] ))
        ))
        // Flow statements
      | tag!("fail!")    => { |_| PStatement::Fail(Token::new("","TODO")) } // TODO proper message
      | tag!("return!") => { |_| PStatement::Return(Token::new("","TODO")) } // TODO proper message
      | tag!("log!")     => { |_| PStatement::Log(Token::new("","TODO")) } // TODO proper message
      | tag!("noop!")    => { |_| PStatement::Noop }
    )
);

/// A state definition defines a state of a resource.
/// It is composed of one or more statements.
#[derive(Debug, PartialEq)]
pub struct PStateDef<'a> {
    pub name: Token<'a>,
    pub resource_name: Token<'a>,
    pub parameters: Vec<PParameter<'a>>,
    pub statements: Vec<PStatement<'a>>,
}
pnamed!(
    pstate_def<PStateDef>,
    sp!(do_parse!(
        resource_name: pidentifier
            >> tag!("state")
            >> name: pidentifier
            >> tag!("(")
            >> parameters: separated_list!(tag!(","), pparameter)
            >> or_fail!(tag!(")"),PError::UnterminatedDelimiter)
            >> tag!("{")
            >> statements: many0!(pstatement)
            >> or_fail!(tag!("}"),PError::UnterminatedDelimiter)
            >> (PStateDef {
                name,
                resource_name,
                parameters,
                statements
            })
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
pnamed!(
    pdeclaration<PDeclaration>,
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
            Err(Err::Failure(Context::Code(_, ErrorKind::Custom(e)))) => {
                Err(PError::from_u32(e).unwrap())
            }
            Err(Err::Error(Context::Code(_, ErrorKind::Custom(e)))) => {
                Err(PError::from_u32(e).unwrap())
            }
            Err(Err::Incomplete(_)) => panic!("Incomplete should never happen"),
            Err(_) => Err(PError::Unknown),
            Ok((x, y)) => Ok((x, y)),
        }
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
    fn test_strings() {
        assert_eq!(
            mapok(escaped_string(pinput("", "\"1hello\\n\\\"Herman\\\"\n\""))),
            Ok(("", ("\"".into(), "1hello\n\"Herman\"\n".to_string())))
        );
        assert_eq!(
            mapok(unescaped_string(pinput(
                "",
                "\"\"\"2hello\\n\"Herman\"\n\"\"\""
            ))),
            Ok(("", ("\"\"\"".into(), "2hello\\n\"Herman\"\n".to_string())))
        );
        assert!(escaped_string(pinput("", "\"2hello\\n\\\"Herman\\\"\n")).is_err());
        assert_eq!(
            mapok(interpolated_string(pinput("", "hello herman"))),
            Ok(("",(vec!["hello herman".into()],Vec::new())))
        );
        assert_eq!(
            mapok(interpolated_string(pinput("", "hello herman 10$$"))),
            Ok(("",(vec!["hello herman 10$".into()],Vec::new())))
        );
        assert_eq!(
            mapok(interpolated_string(pinput("", "hello herman ${variable1} ${var2}"))),
            Ok(("",(vec!["hello herman ".into()," ".into(),"".into()],vec!["variable1".into(),"var2".into()])))
        );
    }

    #[test]
    fn test_value() {
        // TODO other types
        assert_eq!(
            mapok(pvalue(pinput("", "\"\"\"This is a string\"\"\""))),
            Ok((
                "",
                PValue::String("\"\"\"".into(), "This is a string".to_string())
            ))
        );
        assert_eq!(
            mapok(pvalue(pinput("", "\"This is a string bis\""))),
            Ok((
                "",
                PValue::String("\"".into(), "This is a string bis".to_string())
            ))
        );
    }

    #[test]
    fn test_pmetadata() {
        assert_eq!(
            mapok(pmetadata(pinput("", "@key=\"value\""))),
            Ok((
                "",
                PMetadata {
                    key: "key".into(),
                    value: PValue::String("\"".into(), "value".to_string())
                }
            ))
        );
        assert_eq!(
            mapok(pmetadata(pinput("", "@key = \"value\""))),
            Ok((
                "",
                PMetadata {
                    key: "key".into(),
                    value: PValue::String("\"".into(), "value".to_string())
                }
            ))
        );
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
                    default: Some(PValue::String(
                        "\"".into(),
                        "default".to_string()
                    )),
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
    fn test_presource_ref() {
        assert_eq!(
            mapok(presource_ref(pinput("", "hello()"))),
            Ok((
                "",
                ("hello".into(), vec![])
            ))
        );
        assert_eq!(
            mapok(presource_ref(pinput("", "hello3 "))),
            Ok((
                "",
                ("hello3".into(), vec![])
            ))
        );
        assert_eq!(
            mapok(presource_ref(pinput("", "hello ( \"p1\", \"p2\" )"))),
            Ok((
                "",
                ("hello".into(),
                 vec![
                        PValue::String("\"".into(), "p1".to_string()),
                        PValue::String("\"".into(), "p2".to_string())
                    ]
                )
            ))
        );
        assert_eq!(
            mapok(presource_ref(pinput("", "hello2 ( )"))),
            Ok((
                "",
                ("hello2".into(), vec![])
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
                    PCallMode::Enforce,
                    "resource".into(),
                    vec![],
                    "state".into(),
                    Vec::new(),
                    None,
                )
            ))
        );
        assert_eq!(
            mapok(pstatement(pinput("", "resource().state( \"p1\", \"p2\")"))),
            Ok((
                "",
                PStatement::StateCall(
                    PCallMode::Enforce,
                    "resource".into(),
                    vec![],
                    "state".into(),
                    vec![
                        PValue::String("\"".into(), "p1".to_string()),
                        PValue::String("\"".into(), "p2".to_string())
                    ],
                    None,
                )
            ))
        );
        assert_eq!(
            mapok(pstatement(pinput("", "##hello Herman\n"))),
            Ok(("", PStatement::Comment("hello Herman".into())))
        );
        let st = "case { ubuntu => f().g(), debian => a().b() }";
        assert_eq!(
            mapok(pstatement(pinput(
                "",
                st
            ))),
            Ok((
                "",
                PStatement::Case("case".into(), vec![
                    (
                        { let mut s = pinput("", "ubuntu "); s.offset=7; s},
                        vec![pstatement(pinput("", "f().g()")).unwrap().1]
                    ),
                    (
                        { let mut s = pinput("", "debian "); s.offset=26; s},
                        vec![pstatement(pinput("", "a().b()")).unwrap().1]
                    ),
                ])
            ))
        );
    }

    #[test]
    fn test_pstate_def() {
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
                            PCallMode::Enforce,
                            "file".into(),
                            vec![PValue::String("\"".into(), "/tmp".to_string())],
                            "permissions".into(),
                            vec![PValue::String("\"".into(), "root".to_string()), PValue::String("\"".into(), "root".to_string()), PValue::String("\"".into(), "g+w".to_string())],
                            None,
                        )
                    ]
                })
            )));
    }

    #[test]
    fn test_errors() {
        assert_eq!(
            maperr(header(pinput("", "@format=21.5\n"))),
            Err(PError::InvalidFormat)
        );
        assert_eq!(
            maperr(escaped_string(pinput("", "\"2hello"))),
            Err(PError::UnterminatedString)
        );
        assert_eq!(
            maperr(unescaped_string(pinput("", "\"\"\"hello\"\""))),
            Err(PError::UnterminatedString)
        );
        assert_eq!(
            maperr(pvalue(pinput("", "\"\"\"hello\"\""))),
            Err(PError::UnterminatedString)
        );
        assert_eq!(
            maperr(pvalue(pinput("", "\"hello\\x\""))),
            Err(PError::InvalidEscape)
        );
        assert_eq!(
            maperr(pvalue(pinput("", "\"hello\\"))),
            Err(PError::InvalidEscape)
        );
        assert_eq!(
            maperr(penum(pinput("", "enum 2abc { a, b, c }"))),
            Err(PError::InvalidName)
        );
        assert_eq!(
            maperr(penum(pinput("", "enum abc { a, b, }"))),
            Err(PError::UnterminatedDelimiter)
        );
        assert_eq!(
            maperr(penum(pinput("", "enum abc { a, b"))),
            Err(PError::UnterminatedDelimiter)
        );
        assert_eq!(
            maperr(penum_mapping(pinput("", "enum abc ~> { a -> b"))),
            Err(PError::InvalidName)
        );
        assert_eq!(
            maperr(penum_mapping(pinput("", "enum abc ~> def { a -> b"))),
            Err(PError::UnterminatedDelimiter)
        );
        assert_eq!(
            maperr(penum_mapping(pinput("", "enum abc > def { a -> b }"))),
            Err(PError::InvalidSeparator)
        );
        assert_eq!(
            maperr(penum_mapping(pinput("", "enum abc ~> def { a -> b, c d }"))),
            Err(PError::InvalidSeparator)
        );
        assert_eq!(
            maperr(penum_mapping(pinput("", "enum abc ~> def a -> b, c -> d }"))),
            Err(PError::InvalidSeparator)
        );
        assert_eq!(
            maperr(penum_mapping(pinput("", "enum abc ~> def { a -> b c -> d }"))),
            Err(PError::UnterminatedDelimiter)
        );
        assert_eq!(
            maperr(penum_expression(pinput("", "a=~b:"))),
            Err(PError::EnumExpression)
        );
        assert_eq!(
            maperr(penum_expression(pinput("", "a=~"))),
            Err(PError::EnumExpression)
        );
        assert_eq!(
            maperr(interpolated_string(pinput("", "hello${pouet "))),
            Err(PError::UnterminatedDelimiter)
        );
        assert_eq!(
            maperr(pmetadata(pinput("", "@x y"))),
            Err(PError::InvalidSeparator)
        );
    }
}
