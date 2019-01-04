use nom::types::CompleteStr;
use nom::*;
use nom_locate::LocatedSpan;
use nom_locate::position;

// TODO Error management
// TODO Store token location in file
// TODO add more types

// STRUCTURES
//

// All input and output are Located Complete str
pub type PStr<'a> = LocatedSpan<CompleteStr<'a>,&'a str>;

pub fn pstr<'a>(name: &'a str, input: &'a str) -> PStr<'a> {
    LocatedSpan::new(CompleteStr(input),name)
}

pub fn pstr_start<'a>(name: &'a str, input: &'a str, offset: usize, line: u32) -> PStr<'a> {
    let mut span = LocatedSpan::new(CompleteStr(input),name);
    span.offset = offset;
    span.line = line;
    span
}


#[derive(Debug, PartialEq)]
pub struct PHeader {
    pub version: u32,
}

#[derive(Debug, PartialEq)]
pub struct PFile<'a> {
    pub header: PHeader,
    pub code: Vec<PDeclaration<'a>>,
}

type PComment<'a> = Vec<PStr<'a>>;

#[derive(Debug, PartialEq)]
pub struct PMetadata<'a> {
    pub key: PStr<'a>,
    pub value: PValue<'a>,
}

#[derive(Debug, PartialEq)]
pub struct PParameter<'a> {
    pub name: PStr<'a>,
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
pub struct PEnum<'a> {
    pub name: PStr<'a>,
    pub items: Vec<PStr<'a>>,
}

#[derive(Debug, PartialEq)]
pub struct PEnumMapping<'a> {
    pub from: PStr<'a>,
    pub to: PStr<'a>,
    pub mapping: Vec<(PStr<'a>, PStr<'a>)>,
}

#[derive(Debug, PartialEq)]
pub enum PEnumExpression<'a> {
    //       variable        enum              value
    Compare(Option<PStr<'a>>, Option<PStr<'a>>, PStr<'a>),
    And(Box<PEnumExpression<'a>>, Box<PEnumExpression<'a>>),
    Or(Box<PEnumExpression<'a>>, Box<PEnumExpression<'a>>),
    Not(Box<PEnumExpression<'a>>),
    Default,
}

#[derive(Debug, PartialEq)]
pub struct PResourceDef<'a> {
    pub name: PStr<'a>,
    pub parameters: Vec<PParameter<'a>>,
}

#[derive(Debug, PartialEq)]
pub enum PValue<'a> {
    String(String),
    // to make sure we have a reference in this struct because there will be one some day
    #[allow(dead_code)]
    XX(PStr<'a>),
    //VInteger(u64),
    //...
}

#[derive(Debug, PartialEq)]
pub struct PResourceRef<'a> {
    pub name: PStr<'a>,
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
    StateCall(
        Option<PStr<'a>>,  // outcome
        PCallMode,        // mode
        PResourceRef<'a>, // resource
        PStr<'a>,          // state name
        Vec<PValue<'a>>,  // parameters
    ),
    //   list of condition          then
    Case(Vec<(PEnumExpression<'a>, Box<PStatement<'a>>)>),
    // Stop engine
    Fail(PStr<'a>),
    // Inform the user of something
    Log(PStr<'a>),
    // Return a specific outcome
    Return(PStr<'a>),
    // Do nothing
    Noop,
    // TODO condition instance, resource instance, variable definition
}

#[derive(Debug, PartialEq)]
pub struct PStateDef<'a> {
    pub name: PStr<'a>,
    pub resource_name: PStr<'a>,
    pub parameters: Vec<PParameter<'a>>,
    pub statements: Vec<PStatement<'a>>,
}

#[derive(Debug, PartialEq)]
pub enum PDeclaration<'a> {
    Comment(PComment<'a>),
    Metadata(PMetadata<'a>),
    Resource(PResourceDef<'a>),
    State(PStateDef<'a>),
    Enum(PEnum<'a>),
    Mapping(PEnumMapping<'a>),
}

#[derive(Debug, PartialEq)]
pub struct PCode<'a> {
    header: PHeader,
    code: Vec<PDeclaration<'a>>,
}

#[derive(Debug, PartialEq)]
pub enum Error {
    InvalidFormat,
    UnterminatedString,
}

// PARSERS adapter for str instead of byte{]
//

// eat char separators instead of u8
named!(space_s<PStr,PStr>, eat_separator!(&" \t\r\n"[..]));

// ws! for chars instead of u8
// TODO add a strip comment
macro_rules! sp (
    ($i:expr, $($args:tt)*) => (
        {
            sep!($i, space_s, $($args)*)
        }
    )
);

// simplify error repoting in parsers
macro_rules! fail (
    ($i:expr, $code:expr, $submac:ident!( $($args:tt)* )) => (return_error!($i, ErrorKind::Custom($code as u32), $submac!($($args)*)););
    //($i:expr, $code:expr, $f:expr ) => (return_error!($i, ErrorKind::Custom($code as u32), call!($f)););
);
//macro_rules! error (
//    ($i:expr, $code:expr, $submac:ident!( $($args:tt)* )) => (add_return_error!($i, ErrorKind::Custom($code as u32), $submac!($($args)*)););
//    //($i:expr, $code:expr, $f:expr ) => (add_return_error!($i, ErrorKind::Custom($code as u32), call!($f)););
//);

// PARSERS
//

named!(header<PStr,PHeader>,
    do_parse!(
        opt!(preceded!(tag!("#!/"),take_until_and_consume!("\n"))) >>
        // strict parser so that this does not diverge and anything can read the line
        fail!(Error::InvalidFormat,tag!("@format=")) >>
        version: fail!(Error::InvalidFormat,map_res!(take_until!("\n"), |s:PStr| s.fragment.parse::<u32>())) >>
        fail!(Error::InvalidFormat,tag!("\n")) >>
        (PHeader { version })
    )
);

named!(identifier<PStr,PStr>,
    verify!(alphanumeric, |x:PStr| {
        let c=x.fragment.chars().next().unwrap_or(' '); // space is not a valid starting char
        (c as u8 >= 0x41 && c as u8 <= 0x5A) || (c as u8 >= 0x61 && c as u8 <= 0x7A) || (c as u8 >= 0x30 && c as u8 <= 0x39)
    } )
);

//    // Convert to IResult<&[u8], &[u8], ErrorStr>
//    impl From<u32> for PHeader {
//      fn from(i: u32) -> Self {
//        PHeader {version:1}
//      }
//    }
// string
named!(escaped_string<PStr,String>,
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
        fail!(Error::UnterminatedString,tag!("\""))
    )
);

named!(unescaped_string<PStr,String>,
    delimited!(
        // TODO string containing """
        tag!("\"\"\""),
        map!(take_until!("\"\"\""), { |x:PStr| x.to_string() }),
        fail!(Error::UnterminatedString,tag!("\"\"\""))
    )
);

// Value
//
named!(typed_value<PStr,PValue>,
    // TODO other types
    alt!(
        unescaped_string  => { |x| PValue::String(x) }
      | escaped_string    => { |x| PValue::String(x) }
    )
);

// Enums
named!(penum<PStr,PEnum>,
    sp!(do_parse!(
        tag!("enum") >>
        name: identifier >>
        tag!("{") >>
        items: sp!(separated_list!(tag!(","), identifier)) >>
        tag!("}") >>
        (PEnum {name, items})
    ))
);

named!(enum_mapping<PStr,PEnumMapping>,
    sp!(do_parse!(
        tag!("enum") >>
        from: identifier >>
        tag!("~>") >>
        to: identifier >>
        tag!("{") >>
        mapping: sp!(separated_list!(
                tag!(","),
                separated_pair!(
                    alt!(identifier|tag!("*")),
                    tag!("->"),
                    identifier)
            )) >>
        tag!("}") >>
        (PEnumMapping {from, to, mapping})
    ))
);

named!(enum_expression<PStr,PEnumExpression>,
    alt!(enum_or_expression
       | enum_and_expression
       | enum_not_expression
       | enum_atom
       | tag!("default") => { |_| PEnumExpression::Default }
    )
);

named!(enum_atom<PStr,PEnumExpression>,
    sp!(alt!(
        delimited!(tag!("("),enum_expression,tag!(")"))
      | do_parse!(
            var: identifier >>
            tag!("==") >>
            penum: opt!(terminated!(identifier,tag!("::"))) >>
            value: identifier >>
            (PEnumExpression::Compare(Some(var),penum,value))
        )
      | do_parse!(
            penum: opt!(terminated!(identifier,tag!("::"))) >>
            value: identifier >>
            (PEnumExpression::Compare(None,penum,value))
        )
    ))
);

named!(enum_or_expression<PStr,PEnumExpression>,
    sp!(do_parse!(
        left: alt!(enum_and_expression | enum_not_expression | enum_atom) >>
        tag!("||") >>
        right: alt!(enum_or_expression | enum_and_expression | enum_not_expression | enum_atom) >>
        (PEnumExpression::Or(Box::new(left),Box::new(right)))
    ))
);

named!(enum_and_expression<PStr,PEnumExpression>,
    sp!(do_parse!(
        left: alt!(enum_not_expression | enum_atom) >>
        tag!("&&") >>
        right: alt!(enum_and_expression | enum_not_expression | enum_atom) >>
        (PEnumExpression::And(Box::new(left),Box::new(right)))
    ))
);

named!(enum_not_expression<PStr,PEnumExpression>,
    sp!(do_parse!(
        tag!("!") >>
        right: enum_atom >>
        (PEnumExpression::Not(Box::new(right)))
    ))
);

// comments
named!(comment_line<PStr,PStr>,
  preceded!(tag!("##"),
            alt!(take_until_and_consume!("\n")
                |rest
            )
      )
);
named!(comment_line2<PStr,PStr>,
  preceded!(tag!("##"),
            alt!(take_until_and_consume!("\n")
                |rest
            )
      )
);
named!(comment_block<PStr,PComment>,
  many1!(comment_line)
);

// metadata
named!(metadata<PStr,PMetadata>,
  sp!(do_parse!(char!('@') >>
    key: identifier >>
    char!('=') >>
    value: typed_value >>
    (PMetadata {key, value})
  ))
);

// type
named!(typename<PStr,PType>,
  alt!(
    tag!("string")      => { |_| PType::TString }  |
    tag!("int")         => { |_| PType::TInteger } |
    tag!("struct")      => { |_| PType::TStruct }  |
    tag!("list")        => { |_| PType::TList }
  )
);

// Parameters
named!(parameter<PStr,PParameter>,
  sp!(do_parse!(
    ptype: opt!(sp!(terminated!(typename, char!(':')))) >>
    name: identifier >>
    default: opt!(sp!(preceded!(tag!("="),typed_value))) >>
    (PParameter {ptype, name, default})
  ))
);

// ResourceDef
named!(resource_def<PStr,PResourceDef>,
  sp!(do_parse!(
    tag!("resource") >>
    name: identifier >>
    tag!("(") >>
    parameters: separated_list!(
        tag!(","),
        parameter) >>
    tag!(")") >>
    (PResourceDef {name, parameters})
  ))
);

// ResourceRef
named!(resource_ref<PStr,PResourceRef>,
  sp!(do_parse!(
    name: identifier >>
    params: opt!(sp!(do_parse!(tag!("(") >>
        parameters: separated_list!(
            tag!(","),
            typed_value) >>
        tag!(")") >>
        (parameters) ))) >>
    (PResourceRef {name, parameters: params.unwrap_or(vec![])})
  ))
);

// statements
named!(statement<PStr,PStatement>,
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
          resource: resource_ref >>
          tag!(".") >>
          state: identifier >>
          tag!("(") >>
          parameters: separated_list!(
              tag!(","),
              typed_value) >>
          tag!(")") >>
          (PStatement::StateCall(outcome,mode,resource,state,parameters))
      ))
    | comment_block => { |x| PStatement::Comment(x) }
      // case
    | sp!(do_parse!(
          tag!("case") >>
          tag!("{") >>
          cases: separated_list!(tag!(","),
                    do_parse!(
                        expr: enum_expression >>
                        tag!("=>") >>
                        stmt: statement >>
                        ((expr,Box::new(stmt)))
                )) >>
          (PStatement::Case(cases))
      ))
      // if
    | sp!(do_parse!(
          tag!("if") >>
          expr: enum_expression >>
          tag!("=>") >>
          stmt: statement >>
          (PStatement::Case( vec![(expr,Box::new(stmt)), (PEnumExpression::Default,Box::new(PStatement::Log(pstr("","TODO"))))] ))
      ))
      // Flow statements
    | tag!("fail!")    => { |_| PStatement::Fail(pstr("","TODO")) } // TODO proper message
    | tag!("return!!") => { |_| PStatement::Return(pstr("","TODO")) } // TODO proper message
    | tag!("log!")     => { |_| PStatement::Log(pstr("","TODO")) } // TODO proper message
    | tag!("noop!")    => { |_| PStatement::Noop }
  )
);

// state definition
named!(state<PStr,PStateDef>,
  sp!(do_parse!(
      resource_name: identifier >>
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
     (PStateDef {name, resource_name, parameters, statements })
  ))
);

// a file
named!(declaration<PStr,PDeclaration>,
    sp!(alt_complete!(
          resource_def    => { |x| PDeclaration::Resource(x) }
        | metadata      => { |x| PDeclaration::Metadata(x) }
        | state         => { |x| PDeclaration::State(x) }
        | comment_block => { |x| PDeclaration::Comment(x) }
        | penum         => { |x| PDeclaration::Enum(x) }
        | enum_mapping  => { |x| PDeclaration::Mapping(x) }
      ))
);

named!(pub parse<PStr,PFile>,
  do_parse!(
    header: header >>
    code: many0!(declaration) >>
    eof!() >>
    (PFile {header, code} )
  )
);

// TESTS
//

#[cfg(test)]
mod tests {
    use super::*;

    // Adapter to simplify writing tests
    fn mapok<'a, O, E>(
        r: Result<(PStr<'a>, O), Err<PStr<'a>, E>>,
    ) -> Result<(&str, O), Err<PStr<'a>, E>> {
        match r {
            Ok((x, y)) => Ok((*x.fragment, y)),
            Err(e) => Err(e),
        }
    }

    #[test]
    fn test_strings() {
        assert_eq!(
            mapok(escaped_string(pstr("file","\"1hello\\n\\\"Herman\\\"\n\""))),
            Ok(("", "1hello\n\"Herman\"\n".to_string()))
        );
        assert_eq!(
            mapok(unescaped_string(pstr("file",
                "\"\"\"2hello\\n\"Herman\"\n\"\"\""
            ))),
            Ok(("", "2hello\\n\"Herman\"\n".to_string()))
        );
        assert!(escaped_string(pstr("file","\"2hello\\n\\\"Herman\\\"\n")).is_err());
    }

    #[test]
    fn test_enum() {
        assert_eq!(
            mapok(penum(pstr("file","enum abc { a, b, c }"))),
            Ok((
                "",
                PEnum {
                    name: pstr_start("file","abc",5,1),
                    items: vec![pstr_start("file","a",11,1), pstr_start("file","b",14,1), pstr_start("file","c",17,1)]
                }
            ))
        );
        assert_eq!(
            mapok(enum_mapping(pstr("file",
                "enum abc ~> def { a -> d, b -> e, * -> f}"
            ))),
            Ok((
                "",
                PEnumMapping {
                    from: pstr_start("file","abc",5,1),
                    to: pstr_start("file","def",12,1),
                    mapping: vec![(pstr_start("file","a",18,1), pstr_start("file","d",23,1)), (pstr_start("file","b",26,1), pstr_start("file","e",31,1)), (pstr_start("file","*",34,1), pstr_start("file","f",39,1))]
                }
            ))
        );
    }

    #[test]
    fn test_enum_expression() {
        assert_eq!(
            mapok(enum_expression(pstr("file","a==b::c"))),
            Ok(("", PEnumExpression::Compare(Some(pstr_start("file","a",0,1)), Some(pstr_start("file","b",3,1)), pstr_start("file","c",6,1))))
        );
        assert_eq!(
            mapok(enum_expression(pstr("file","a==bc"))),
            Ok(("", PEnumExpression::Compare(Some(pstr_start("file","a",0,1)), None, pstr_start("file","bc",3,1))))
        );
        assert_eq!(
            mapok(enum_expression(pstr("file","bc"))),
            Ok(("", PEnumExpression::Compare(None, None, pstr_start("file","bc",0,1))))
        );
        assert_eq!(
            mapok(enum_expression(pstr("file","(a == b::hello)"))),
            Ok(("", PEnumExpression::Compare(Some(pstr_start("file","a",1,1)), Some(pstr_start("file","b",6,1)), pstr_start("file","hello",9,1))))
        );
        assert_eq!(
            mapok(enum_expression(pstr("file","bc&&(a||b==hello::g)"))),
            Ok((
                "",
                PEnumExpression::And(
                    Box::new(PEnumExpression::Compare(None, None, pstr_start("file","bc",0,1))),
                    Box::new(PEnumExpression::Or(
                        Box::new(PEnumExpression::Compare(None, None, pstr_start("file","a",5,1))),
                        Box::new(PEnumExpression::Compare(Some(pstr_start("file","b",8,1)), Some(pstr_start("file","hello",11,1)), pstr_start("file","g",18,1)))
                    )),
                )
            ))
        );
        assert_eq!(
            mapok(enum_expression(pstr("file","! a == hello"))),
            Ok((
                "",
                PEnumExpression::Not(Box::new(PEnumExpression::Compare(Some(pstr_start("file","a",2,1)), None, pstr_start("file","hello",7,1))))
            ))
        );
    }

    #[test]
    fn test_comment_line2() {
        assert_eq!(
            comment_line2(pstr("file","##hello Herman\n")),
            Ok((pstr_start("file","",15,2), pstr_start("file","hello Herman",2,1)))
        );
    }
    #[test]
    fn test_comment_line() {
        assert_eq!(
            mapok(comment_line(pstr("file","##hello Herman\n"))),
            Ok(("", pstr_start("file","hello Herman",2,1)))
        );
        assert_eq!(
            mapok(comment_line(pstr("file","##hello Herman\nHola"))),
            Ok(("Hola", pstr_start("file","hello Herman",2,1)))
        );
        assert_eq!(
            mapok(comment_line(pstr("file","##hello Herman!"))),
            Ok(("", pstr_start("file","hello Herman!",2,1)))
        );
        assert_eq!(
            mapok(comment_line(pstr("file","##hello\nHerman\n"))),
            Ok(("Herman\n", pstr_start("file","hello",2,1)))
        );
        assert!(comment_line(pstr("file","hello\nHerman\n")).is_err());
    }

    #[test]
    fn test_comment_block() {
        assert_eq!(
            mapok(comment_block(pstr("file","##hello Herman\n"))),
            Ok(("", vec![pstr_start("file","hello Herman",2,1)]))
        );
        assert_eq!(
            mapok(comment_block(pstr("file","##hello Herman!"))),
            Ok(("", vec![pstr_start("file","hello Herman!",2,1)]))
        );
        assert_eq!(
            mapok(comment_block(pstr("file","##hello\n##Herman\n"))),
            Ok(("", vec![pstr_start("file","hello",2,1), pstr_start("file","Herman",10,2)]))
        );
        assert!(comment_block(pstr("file","hello Herman")).is_err());
    }

    #[test]
    fn test_metadata() {
        assert_eq!(
            mapok(metadata(pstr("file","@key=\"value\""))),
            Ok((
                "",
                PMetadata {
                    key: pstr_start("file","key",1,1),
                    value: PValue::String("value".to_string())
                }
            ))
        );
        assert_eq!(
            mapok(metadata(pstr("file","@key = \"value\""))),
            Ok((
                "",
                PMetadata {
                    key: pstr_start("file","key",1,1),
                    value: PValue::String("value".to_string())
                }
            ))
        );
    }

    #[test]
    fn test_identifier() {
        assert_eq!(mapok(identifier(pstr("file","simple "))),
                   Ok((" ", pstr_start("file","simple",0,1))));
        assert_eq!(mapok(identifier(pstr("file","simple?"))),
                   Ok(("?", pstr_start("file","simple",0,1))));
        assert_eq!(mapok(identifier(pstr("file","5impl3 "))),
                   Ok((" ", pstr_start("file","5impl3",0,1))));
        assert!(identifier(pstr("file","%imple ")).is_err());
    }

    #[test]
    fn test_parameter() {
        assert_eq!(
            mapok(parameter(pstr("file","hello "))),
            Ok((
                "",
                PParameter {
                    name: pstr_start("file","hello",0,1),
                    ptype: None,
                    default: None,
                }
            ))
        );
        assert_eq!(
            mapok(parameter(pstr("file","string:hello "))),
            Ok((
                "",
                PParameter {
                    name: pstr_start("file","hello",7,1),
                    ptype: Some(PType::TString),
                    default: None,
                }
            ))
        );
        assert_eq!(
            mapok(parameter(pstr("file"," string : hello "))),
            Ok((
                "",
                PParameter {
                    name: pstr_start("file","hello",10,1),
                    ptype: Some(PType::TString),
                    default: None,
                }
            ))
        );
        assert_eq!(
            mapok(parameter(pstr("file"," string : hello=\"default\""))),
            Ok((
                "",
                PParameter {
                    name: pstr_start("file","hello",10,1),
                    ptype: Some(PType::TString),
                    default: Some(PValue::String("default".to_string())),
                }
            ))
        );
    }

    #[test]
    fn test_resource_def() {
        assert_eq!(
            mapok(resource_def(pstr("file","resource hello()"))),
            Ok((
                "",
                PResourceDef {
                    name: pstr_start("file","hello",9,1),
                    parameters: vec![]
                }
            ))
        );
        assert_eq!(
            mapok(resource_def(pstr("file","resource  hello2 ( )"))),
            Ok((
                "",
                PResourceDef {
                    name: pstr_start("file","hello2",10,1),
                    parameters: vec![]
                }
            ))
        );
        assert_eq!(
            mapok(resource_def(pstr("file","resource hello (string: p1, p2)"))),
            Ok((
                "",
                PResourceDef {
                    name: pstr_start("file","hello",9,1),
                    parameters: vec![
                        PParameter {
                            name: pstr_start("file","p1",24,1),
                            ptype: Some(PType::TString),
                            default: None,
                        },
                        PParameter {
                            name: pstr_start("file","p2",28,1),
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
            mapok(typed_value(pstr("file","\"\"\"This is a string\"\"\""))),
            Ok(("", PValue::String("This is a string".to_string())))
        );
        assert_eq!(
            mapok(typed_value(pstr("file","\"This is a string bis\""))),
            Ok(("", PValue::String("This is a string bis".to_string())))
        );
    }

    #[test]
    fn test_headers() {
        assert_eq!(
            mapok(header(pstr("file","#!/bin/bash\n@format=1\n"))),
            Ok(("", PHeader { version: 1 }))
        );
        assert_eq!(
            mapok(header(pstr("file","@format=21\n"))),
            Ok(("", PHeader { version: 21 }))
        );
        assert!(header(pstr("file","@format=21.5\n")).is_err());
    }

    #[test]
    fn test_resource_ref() {
        assert_eq!(
            mapok(resource_ref(pstr("file","hello()"))),
            Ok((
                "",
                PResourceRef {
                    name: pstr_start("file","hello",0,1),
                    parameters: vec![]
                }
            ))
        );
        assert_eq!(
            mapok(resource_ref(pstr("file","hello3 "))),
            Ok((
                "",
                PResourceRef {
                    name: pstr_start("file","hello3",0,1),
                    parameters: vec![]
                }
            ))
        );
        assert_eq!(
            mapok(resource_ref(pstr("file","hello ( \"p1\", \"p2\" )"))),
            Ok((
                "",
                PResourceRef {
                    name: pstr_start("file","hello",0,1),
                    parameters: vec![
                        PValue::String("p1".to_string()),
                        PValue::String("p2".to_string())
                    ]
                }
            ))
        );
        assert_eq!(
            mapok(resource_ref(pstr("file","hello2 ( )"))),
            Ok((
                "",
                PResourceRef {
                    name: pstr_start("file","hello2",0,1),
                    parameters: vec![]
                }
            ))
        );
    }

    #[test]
    fn test_statement() {
        assert_eq!(
            mapok(statement(pstr("file","resource().state()"))),
            Ok((
                "",
                PStatement::StateCall(
                    None,
                    PCallMode::Enforce,
                    PResourceRef {
                        name: pstr_start("file","resource",0,1),
                        parameters: vec![]
                    },
                    pstr_start("file","state",11,1),
                    vec![]
                )
            ))
        );
        assert_eq!(
            mapok(statement(pstr("file","resource().state( \"p1\", \"p2\")"))),
            Ok((
                "",
                PStatement::StateCall(
                    None,
                    PCallMode::Enforce,
                    PResourceRef {
                        name: pstr_start("file","resource",0,1),
                        parameters: vec![]
                    },
                    pstr_start("file","state",11,1),
                    vec![
                        PValue::String("p1".to_string()),
                        PValue::String("p2".to_string())
                    ]
                )
            ))
        );
        assert_eq!(
            mapok(statement(pstr("file","##hello Herman\n"))),
            Ok(("", PStatement::Comment(vec![pstr_start("file","hello Herman",2,1)])))
        );
    }

    #[test]
    fn test_declaration() {
        assert_eq!(
            mapok(declaration(pstr("file","ntp state configuration ()\n{\n  file(\"/tmp\").permissions(\"root\", \"root\", \"g+w\")\n}\n"))),
            Ok(("\n",
                PDeclaration::State(PStateDef {
                    name: pstr_start("file","configuration",10,1),
                    resource_name: pstr_start("file","ntp",0,1),
                    parameters: vec![],
                    statements: vec![
                        PStatement::StateCall(
                            None, PCallMode::Enforce,
                            PResourceRef {
                                name: pstr_start("file","file",31,3), 
                                parameters: vec![PValue::String("/tmp".to_string())] }, 
                            pstr_start("file","permissions",44,3),
                            vec![PValue::String("root".to_string()), PValue::String("root".to_string()), PValue::String("g+w".to_string())]
                        )
                    ]
                })
            )));
    }

    #[test]
    fn test_state() {
        assert_eq!(
            mapok(state(pstr("file","resource state configuration() {}"))),
            Ok((
                "",
                PStateDef {
                    name: pstr_start("file","configuration",15,1),
                    resource_name: pstr_start("file","resource",0,1),
                    parameters: vec![],
                    statements: vec![]
                }
            ))
        );
    }
}
