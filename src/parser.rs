use nom::types::CompleteStr;
use nom_locate::LocatedSpan;
use nom::*;

// TODO Error management
// TODO Store token location in file
// TODO Whole file parsing
// TODO add more types

// STRUCTURES
//

// All input are Located Complete str
pub type PInput<'a> = LocatedSpan<CompleteStr<'a>>;

pub fn pinput(input: &str) -> PInput {
    LocatedSpan::new(CompleteStr(input))
}

#[derive(Debug, PartialEq)]
pub struct PHeader {
    pub version: u32,
}

type PComment<'a> = Vec<&'a str>;

#[derive(Debug, PartialEq)]
pub struct PMetadata<'a> {
    pub key: &'a str,
    pub value: PValue<'a>,
}

#[derive(Debug, PartialEq)]
pub struct PParameter<'a> {
    pub name: &'a str,
    pub ptype: Option<PType>,
    pub default: Option<PValue<'a>>,
}

#[derive(Debug, PartialEq)]
pub enum PType {
    TString,
    TInteger,
    TStruct,
    TList,
}

#[derive(Debug, PartialEq)]
pub struct PSet<'a> {
    pub name: &'a str,
    pub items: Vec<&'a str>,
}

#[derive(Debug, PartialEq)]
pub struct PSetMapping<'a> {
    pub from: &'a str,
    pub to: &'a str,
    pub mapping: Vec<(&'a str, &'a str)>,
}

#[derive(Debug, PartialEq)]
pub enum PSetExpression<'a> {
    //      variable         set                      value
    Compare(&'a str, Option<&'a str>, &'a str),
    //       set                      value
    Classify(Option<&'a str>, &'a str),
    And(Box<PSetExpression<'a>>, Box<PSetExpression<'a>>),
    Or(Box<PSetExpression<'a>>, Box<PSetExpression<'a>>),
    Not(Box<PSetExpression<'a>>),
}

#[derive(Debug, PartialEq)]
pub struct PObjectDef<'a> {
    pub name: &'a str,
    pub parameters: Vec<PParameter<'a>>,
}

#[derive(Debug, PartialEq)]
pub enum PValue<'a> {
    String(String),
    XX(&'a str),
    //VInteger(u64),
    //...
}

#[derive(Debug, PartialEq)]
pub struct PObjectRef<'a> {
    pub name: &'a str,
    pub parameters: Vec<PValue<'a>>,
}

#[derive(Debug, PartialEq)]
pub enum PCallMode {
    Enforce,
    Condition,
    Check,
    CheckNot,
}

#[derive(Debug, PartialEq)]
pub enum PStatement<'a> {
    Comment(PComment<'a>),
    //        outcome                  mode       object          state name       parameters
    StateCall(
        Option<&'a str>,
        PCallMode,
        PObjectRef<'a>,
        &'a str,
        Vec<PValue<'a>>,
    ),
    //   list of condition          then
    Case(Vec<(PSetExpression<'a>, Box<PStatement<'a>>)>),
    // condition          then
    If(PSetExpression<'a>, Box<PStatement<'a>>),
    // Stop engine
    Fail(&'a str),
    // Inform the user of something
    Log(&'a str),
    // Do nothing
    Noop,
    // TODO condition instance, object instance, variable definition
}

#[derive(Debug, PartialEq)]
pub struct PStateDef<'a> {
    pub name: &'a str,
    pub object_name: &'a str,
    pub parameters: Vec<PParameter<'a>>,
    pub statements: Vec<PStatement<'a>>,
}

#[derive(Debug, PartialEq)]
pub enum PDeclaration<'a> {
    Comment(PComment<'a>),
    Metadata(PMetadata<'a>),
    Object(PObjectDef<'a>),
    State(PStateDef<'a>),
    Set(PSet<'a>),
    Mapping(PSetMapping<'a>),
}

#[derive(Debug, PartialEq)]
pub struct PCode<'a> {
    header: PHeader,
    code: Vec<PDeclaration<'a>>,
}

#[derive(Debug, PartialEq)]
pub enum Error {
    UnterminatedString,
}

// PARSERS adapter for str instead of byte{]
//

// eat char separators instead of u8
named!(space_s<PInput,PInput>, eat_separator!(&" \t\r\n"[..]));

// ws! for chars instead of u8
// TODO add a strip comment
macro_rules! sp (
  ($i:expr, $($args:tt)*) => (
    {
      sep!($i, space_s, $($args)*)
    }
  )
);

// PARSERS
//

named!(pub header<PInput,PHeader>,
  do_parse!(
    opt!(preceded!(tag!("#!/"),take_until_and_consume!("\n"))) >>
    // strict parser so that this does not diverge and anything can read the line
    tag!("@format=") >>
    version: map_res!(take_until!("\n"), |s:PInput| s.fragment.parse::<u32>()) >>
    tag!("\n") >>
    (PHeader { version })
  )
);

named!(identifier<PInput,&str>,
    map!(
        verify!(alphanumeric, |x:PInput| {
            let c=x.fragment.chars().next().unwrap_or(' '); // space is not a valid starting char
            (c as u8 >= 0x41 && c as u8 <= 0x5A) || (c as u8 >= 0x61 && c as u8 <= 0x7A) || (c as u8 >= 0x30 && c as u8 <= 0x39)
        } ),
        |x| *x.fragment)
);

//    // Convert to IResult<&[u8], &[u8], ErrorStr>
//    impl From<u32> for PHeader {
//      fn from(i: u32) -> Self {
//        PHeader {version:1}
//      }
//    }
// string
named!(escaped_string<PInput,String>,
//       fix_error!(PHeader,
    delimited!(
        tag!("\""), 
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
        add_return_error!(ErrorKind::Custom(Error::UnterminatedString as u32),tag!("\""))
    )
);

named!(unescaped_string<PInput,String>,
    delimited!(
        // TODO string containing """
        tag!("\"\"\""),
        map!(take_until!("\"\"\""), { |x:PInput| x.to_string() }),
        return_error!(ErrorKind::Custom(Error::UnterminatedString as u32),tag!("\"\"\""))
    )
);

// Value
//
named!(typed_value<PInput,PValue>,
    // TODO other types
    alt!(
        unescaped_string  => { |x| PValue::String(x) }
      | escaped_string    => { |x| PValue::String(x) }
    )
);

// Sets
named!(set<PInput,PSet>,
    sp!(do_parse!(
        tag!("set") >>
        name: identifier >>
        tag!("{") >>
        items: sp!(separated_list!(tag!(","), identifier)) >>
        tag!("}") >>
        (PSet {name, items})
    ))
);

named!(set_mapping<PInput,PSetMapping>,
    sp!(do_parse!(
        tag!("set") >>
        from: identifier >>
        tag!("->") >>
        to: identifier >>
        tag!("{") >>
        mapping: sp!(separated_list!(
                tag!(","),
                separated_pair!(
                    alt!(identifier|map!(tag!("*"),|x| *x.fragment)),
                    tag!("->"),
                    identifier)
            )) >>
        tag!("}") >>
        (PSetMapping {from, to, mapping})
    ))
);

named!(set_expression<PInput,PSetExpression>,
    alt!(set_or_expression
       | set_and_expression
       | set_not_expression
       | set_atom
    )
);

named!(set_atom<PInput,PSetExpression>,
    sp!(alt!(
        delimited!(tag!("("),set_expression,tag!(")"))
      | do_parse!(
            var: identifier >>
            tag!("==") >>
            set: opt!(terminated!(identifier,tag!("/"))) >>
            value: identifier >>
            (PSetExpression::Compare(var,set,value))
        )
      | do_parse!(
            set: opt!(terminated!(identifier,tag!("/"))) >>
            value: identifier >>
            (PSetExpression::Classify(set,value))
        )
    ))
);

named!(set_or_expression<PInput,PSetExpression>,
    sp!(do_parse!(
        left: alt!(set_and_expression | set_not_expression | set_atom) >>
        tag!("||") >>
        right: alt!(set_or_expression | set_and_expression | set_not_expression | set_atom) >>
        (PSetExpression::Or(Box::new(left),Box::new(right)))
    ))
);

named!(set_and_expression<PInput,PSetExpression>,
    sp!(do_parse!(
        left: alt!(set_not_expression | set_atom) >>
        tag!("&&") >>
        right: alt!(set_and_expression | set_not_expression | set_atom) >>
        (PSetExpression::And(Box::new(left),Box::new(right)))
    ))
);

named!(set_not_expression<PInput,PSetExpression>,
    sp!(do_parse!(
        tag!("!") >>
        right: set_atom >>
        (PSetExpression::Not(Box::new(right)))
    ))
);

// comments
named!(comment_line<PInput,&str>,
  map!(preceded!(tag!("##"),
            alt!(take_until_and_consume!("\n")
                |rest
            )
      ), |x| *x.fragment )
);
named!(comment_block<PInput,PComment>,
  many1!(comment_line)
);

// metadata
named!(metadata<PInput,PMetadata>,
  sp!(do_parse!(char!('@') >>
    key: identifier >>
    char!('=') >>
    value: typed_value >>
    (PMetadata {key, value})
  ))
);

// type
named!(typename<PInput,PType>,
  alt!(
    tag!("string")      => { |_| PType::TString }  |
    tag!("int")         => { |_| PType::TInteger } |
    tag!("struct")      => { |_| PType::TStruct }  |
    tag!("list")        => { |_| PType::TList }
  )
);

// Parameters
named!(parameter<PInput,PParameter>,
  sp!(do_parse!(
    ptype: opt!(sp!(terminated!(typename, char!(':')))) >>
    name: identifier >>
    default: opt!(sp!(preceded!(tag!("="),typed_value))) >>
    (PParameter {ptype, name, default})
  ))
);

// ObjectDef
named!(object_def<PInput,PObjectDef>,
  sp!(do_parse!(
    tag!("object") >>
    name: identifier >>
    tag!("(") >>
    parameters: separated_list!(
        tag!(","),
        parameter) >>
    tag!(")") >>
    (PObjectDef {name, parameters})
  ))
);

// ObjectRef
named!(object_ref<PInput,PObjectRef>,
  sp!(do_parse!(
    name: identifier >>
    params: opt!(sp!(do_parse!(tag!("(") >>
        parameters: separated_list!(
            tag!(","),
            typed_value) >>
        tag!(")") >>
        (parameters) ))) >>
    (PObjectRef {name, parameters: params.unwrap_or(vec![])})
  ))
);

// statements
named!(statement<PInput,PStatement>,
  alt!(
      // state call
      sp!(do_parse!(
          outcome: opt!(terminated!(identifier,tag!("="))) >>
          mode: alt!(
                    tag!("?") => { |_| PCallMode::Condition } |
                    pair!(tag!("not"), tag!("!")) => { |_| PCallMode::CheckNot }     |
                    tag!("!") => { |_| PCallMode::Check }     |
                    value!(PCallMode::Enforce)
                ) >>
          object: object_ref >>
          tag!(".") >>
          state: identifier >>
          tag!("(") >>
          parameters: separated_list!(
              tag!(","),
              typed_value) >>
          tag!(")") >>
          (PStatement::StateCall(outcome,mode,object,state,parameters))
      ))
    | comment_block => { |x| PStatement::Comment(x) }
      // case
    | sp!(do_parse!(
          tag!("case") >>
          tag!("{") >>
          cases: separated_list!(tag!(","),
                    do_parse!(
                        expr: set_expression >>
                        tag!("=>") >>
                        stmt: statement >>
                        ((expr,Box::new(stmt)))
                )) >>
          (PStatement::Case(cases))
      ))
      // if
    | sp!(do_parse!(
          tag!("if") >>
          expr: set_expression >>
          tag!("=>") >>
          stmt: statement >>
          (PStatement::If(expr,Box::new(stmt)))
      ))
      // Flow statements
    | tag!("fail!") => { |_| PStatement::Fail("failed") } // TODO proper message
    | tag!("log!")  => { |_| PStatement::Log("failed") } // TODO proper message
    | tag!("noop!") => { |_| PStatement::Noop }
  )
);

// state definition
named!(state<PInput,PStateDef>,
  sp!(do_parse!(
      object_name: identifier >>
      tag!("state") >>
      name: identifier >>
      tag!("(") >>
      parameters: separated_list!(
         tag!(","),
         parameter) >>
     tag!(")") >>
     tag!("{") >>
     statements: many0!(statement) >>
     tag!("}") >>
     (PStateDef {name, object_name, parameters, statements })
  ))
);

// a file
named!(declaration<PInput,PDeclaration>,
    sp!(alt_complete!(
          object_def    => { |x| PDeclaration::Object(x) }
        | metadata      => { |x| PDeclaration::Metadata(x) }
        | state         => { |x| PDeclaration::State(x) }
        | comment_block => { |x| PDeclaration::Comment(x) }
        | set           => { |x| PDeclaration::Set(x) }
        | set_mapping   => { |x| PDeclaration::Mapping(x) }
      ))
);

//pub fn parse<'a>(input: PInput<'a>) -> IResult<PInput<'a>, PCode<'a>>
//{
//   let header = try!(header(input));
//   if header.version != 0 {
//       return Err(Err::Failure);
//   }
//
//}
named!(pub code<PInput,Vec<PDeclaration>>,
  do_parse!(
    x: many0!(declaration) >>
    eof!() >>
    (x)
  )
);

// TESTS
//

#[cfg(test)]
mod tests {
    use super::*;

    // Adapter to simplify writing tests
    fn mapok<'a,O,E>(r: Result<(PInput<'a>, O), Err<PInput<'a>, E>>) -> Result<(&str, O), Err<PInput, E>> {
        match r {
            Ok((x,y)) => Ok((*x.fragment,y)),
            Err(e) => Err(e)
        }
    }

    #[test]
    fn test_strings() {
        assert_eq!(
            mapok(escaped_string(pinput("\"1hello\\n\\\"Herman\\\"\n\""))),
            Ok(("", "1hello\n\"Herman\"\n".to_string()))
        );
        assert_eq!(
            mapok(unescaped_string(pinput("\"\"\"2hello\\n\"Herman\"\n\"\"\""))),
            Ok(("", "2hello\\n\"Herman\"\n".to_string()))
        );
        assert!(escaped_string(pinput("\"2hello\\n\\\"Herman\\\"\n")).is_err());
    }

    #[test]
    fn test_set() {
        assert_eq!(
            mapok(set(pinput("set abc { a, b, c }"))),
            Ok((
                "",
                PSet {
                    name: "abc",
                    items: vec!["a", "b", "c"]
                }
            ))
        );
        assert_eq!(
            mapok(set_mapping(pinput("set abc -> def { a -> d, b -> e, * -> f}"))),
            Ok((
                "",
                PSetMapping {
                    from: "abc",
                    to: "def",
                    mapping: vec![
                        ("a", "d"),
                        ("b", "e"),
                        ("*", "f")
                    ]
                }
            ))
        );
    }

    #[test]
    fn test_set_expression() {
        assert_eq!(
            mapok(set_expression(pinput("a==b/c"))),
            Ok((
                "",
                PSetExpression::Compare("a", Some("b"), "c")
            ))
        );
        assert_eq!(
            mapok(set_expression(pinput("a==bc"))),
            Ok((
                "",
                PSetExpression::Compare("a", None, "bc")
            ))
        );
        assert_eq!(
            mapok(set_expression(pinput("bc"))),
            Ok((
                "",
                PSetExpression::Classify(None, "bc")
            ))
        );
        assert_eq!(
            mapok(set_expression(pinput("(a == b/hello)"))),
            Ok((
                "",
                PSetExpression::Compare(
                    "a",
                    Some("b"),
                    "hello"
                )
            ))
        );
        assert_eq!(
            mapok(set_expression(pinput("bc&&(a||b==hello/g)"))),
            Ok((
                "",
                PSetExpression::And(
                    Box::new(PSetExpression::Classify(None, "bc")),
                    Box::new(PSetExpression::Or(
                        Box::new(PSetExpression::Classify(None, "a")),
                        Box::new(PSetExpression::Compare(
                            "b",
                            Some("hello"),
                            "g"
                        ))
                    )),
                )
            ))
        );
        assert_eq!(
            mapok(set_expression(pinput("! a == hello"))),
            Ok((
                "",
                PSetExpression::Not(Box::new(PSetExpression::Compare(
                    "a",
                    None,
                    "hello"
                )))
            ))
        );
    }

    #[test]
    fn test_comment_line() {
        assert_eq!(
            mapok(comment_line(pinput("##hello Herman\n"))),
            Ok(("", "hello Herman"))
        );
        assert_eq!(
            mapok(comment_line(pinput("##hello Herman\nHola"))),
            Ok(("Hola", "hello Herman"))
        );
        assert_eq!(
            mapok(comment_line(pinput("##hello Herman!"))),
            Ok(("", "hello Herman!"))
        );
        assert_eq!(
            mapok(comment_line(pinput("##hello\nHerman\n"))),
            Ok(("Herman\n", "hello"))
        );
        assert!(comment_line(pinput("hello\nHerman\n")).is_err());
    }

    #[test]
    fn test_comment_block() {
        assert_eq!(
            mapok(comment_block(pinput("##hello Herman\n"))),
            Ok(("", vec!["hello Herman"]))
        );
        assert_eq!(
            mapok(comment_block(pinput("##hello Herman!"))),
            Ok(("", vec!["hello Herman!"]))
        );
        assert_eq!(
            mapok(comment_block(pinput("##hello\n##Herman\n"))),
            Ok((
                "",
                vec!["hello", "Herman"]
            ))
        );
        assert!(comment_block(pinput("hello Herman")).is_err());
    }

    #[test]
    fn test_metadata() {
        assert_eq!(
            mapok(metadata(pinput("@key=\"value\""))),
            Ok((
                "",
                PMetadata {
                    key: "key",
                    value: PValue::String("value".to_string())
                }
            ))
        );
        assert_eq!(
            mapok(metadata(pinput("@key = \"value\""))),
            Ok((
                "",
                PMetadata {
                    key: "key",
                    value: PValue::String("value".to_string())
                }
            ))
        );
    }

    #[test]
    fn test_identifier() {
        assert_eq!(
            mapok(identifier(pinput("simple "))),
            Ok((" ", "simple"))
        );
        assert_eq!(
            mapok(identifier(pinput("simple?"))),
            Ok(("?", "simple"))
        );
        assert_eq!(
            mapok(identifier(pinput("5impl3 "))),
            Ok((" ", "5impl3"))
        );
        assert!(identifier(pinput("%imple ")).is_err());
    }

    #[test]
    fn test_parameter() {
        assert_eq!(
            mapok(parameter(pinput("hello "))),
            Ok((
                "",
                PParameter {
                    name: "hello",
                    ptype: None,
                    default: None,
                }
            ))
        );
        assert_eq!(
            mapok(parameter(pinput("string:hello "))),
            Ok((
                "",
                PParameter {
                    name: "hello",
                    ptype: Some(PType::TString),
                    default: None,
                }
            ))
        );
        assert_eq!(
            mapok(parameter(pinput(" string : hello "))),
            Ok((
                "",
                PParameter {
                    name: "hello",
                    ptype: Some(PType::TString),
                    default: None,
                }
            ))
        );
        assert_eq!(
            mapok(parameter(pinput(" string : hello=\"default\""))),
            Ok((
                "",
                PParameter {
                    name: "hello",
                    ptype: Some(PType::TString),
                    default: Some(PValue::String("default".to_string())),
                }
            ))
        );
    }

    #[test]
    fn test_object_def() {
        assert_eq!(
            mapok(object_def(pinput("object hello()"))),
            Ok((
                "",
                PObjectDef {
                    name: "hello",
                    parameters: vec![]
                }
            ))
        );
        assert_eq!(
            mapok(object_def(pinput("object  hello2 ( )"))),
            Ok((
                "",
                PObjectDef {
                    name: "hello2",
                    parameters: vec![]
                }
            ))
        );
        assert_eq!(
            mapok(object_def(pinput("object hello (string: p1, p2)"))),
            Ok((
                "",
                PObjectDef {
                    name: "hello",
                    parameters: vec![
                        PParameter {
                            name: "p1",
                            ptype: Some(PType::TString),
                            default: None,
                        },
                        PParameter {
                            name: "p2",
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
            mapok(typed_value(pinput("\"\"\"This is a string\"\"\""))),
            Ok((
                "",
                PValue::String("This is a string".to_string())
            ))
        );
        assert_eq!(
            mapok(typed_value(pinput("\"This is a string bis\""))),
            Ok((
                "",
                PValue::String("This is a string bis".to_string())
            ))
        );
    }

    #[test]
    fn test_headers() {
        assert_eq!(
            mapok(header(pinput("#!/bin/bash\n@format=1\n"))),
            Ok(("", PHeader { version: 1 }))
        );
        assert_eq!(
            mapok(header(pinput("@format=21\n"))),
            Ok(("", PHeader { version: 21 }))
        );
        assert!(header(pinput("@format=21.5\n")).is_err());
    }

    #[test]
    fn test_object_ref() {
        assert_eq!(
            mapok(object_ref(pinput("hello()"))),
            Ok((
                "",
                PObjectRef {
                    name: "hello",
                    parameters: vec![]
                }
            ))
        );
        assert_eq!(
            mapok(object_ref(pinput("hello3 "))),
            Ok((
                "",
                PObjectRef {
                    name: "hello3",
                    parameters: vec![]
                }
            ))
        );
        assert_eq!(
            mapok(object_ref(pinput("hello ( \"p1\", \"p2\" )"))),
            Ok((
                "",
                PObjectRef {
                    name: "hello",
                    parameters: vec![
                        PValue::String("p1".to_string()),
                        PValue::String("p2".to_string())
                    ]
                }
            ))
        );
        assert_eq!(
            mapok(object_ref(pinput("hello2 ( )"))),
            Ok((
                "",
                PObjectRef {
                    name: "hello2",
                    parameters: vec![]
                }
            ))
        );
    }

    #[test]
    fn test_statement() {
        assert_eq!(
            mapok(statement(pinput("object().state()"))),
            Ok((
                "",
                PStatement::StateCall(
                    None,
                    PCallMode::Enforce,
                    PObjectRef {
                        name: "object",
                        parameters: vec![]
                    },
                    "state",
                    vec![]
                )
            ))
        );
        assert_eq!(
            mapok(statement(pinput("object().state( \"p1\", \"p2\")"))),
            Ok((
                "",
                PStatement::StateCall(
                    None,
                    PCallMode::Enforce,
                    PObjectRef {
                        name: "object",
                        parameters: vec![]
                    },
                    "state",
                    vec![
                        PValue::String("p1".to_string()),
                        PValue::String("p2".to_string())
                    ]
                )
            ))
        );
        assert_eq!(
            mapok(statement(pinput("##hello Herman\n"))),
            Ok((
                "",
                PStatement::Comment(vec!["hello Herman"])
            ))
        );
    }

    #[test]
    fn test_declaration() {
        assert_eq!(
            mapok(declaration(pinput("ntp state configuration ()\n{\n  file(\"/tmp\").permissions(\"root\", \"root\", \"g+w\")\n}\n"))),
            Ok(("\n",
                PDeclaration::State(PStateDef {
                    name: "configuration",
                    object_name: "ntp",
                    parameters: vec![],
                    statements: vec![
                        PStatement::StateCall(
                            None, PCallMode::Enforce,
                            PObjectRef {
                                name: "file", 
                                parameters: vec![PValue::String("/tmp".to_string())] }, 
                            "permissions",
                            vec![PValue::String("root".to_string()), PValue::String("root".to_string()), PValue::String("g+w".to_string())]
                        )
                    ]
                })
            )));
    }

    #[test]
    fn test_state() {
        assert_eq!(
            mapok(state(pinput("object state configuration() {}"))),
            Ok((
                "",
                PStateDef {
                    name: "configuration",
                    object_name: "object",
                    parameters: vec![],
                    statements: vec![]
                }
            ))
        );
    }
}
