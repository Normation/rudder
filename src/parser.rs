use nom::types::CompleteStr;
use nom::*;

// As recommended by nom, we use CompleteStr everywhere
// They are almost as str so this should not be a problem

// STRUCTURES
//

#[derive(Debug, PartialEq)]
pub struct PHeader {
    pub version: u32,
}

type PComment<'a> = Vec<CompleteStr<'a>>;

#[derive(Debug, PartialEq)]
pub struct PMetadata<'a> {
    pub key: CompleteStr<'a>,
    pub value: PValue<'a>,
}

#[derive(Debug, PartialEq)]
pub struct PParameter<'a> {
    pub name: CompleteStr<'a>,
    // TODO store default value in type
    pub ptype: Option<PType>,
}

#[derive(Debug, PartialEq)]
pub enum PType {
    TString,
    TInteger,
    TStruct,
    TList,
}

#[derive(Debug, PartialEq)]
pub struct PObjectDef<'a> {
    pub name: CompleteStr<'a>,
    pub parameters: Vec<PParameter<'a>>,
}

#[derive(Debug, PartialEq)]
pub enum PValue<'a> {
    VString(CompleteStr<'a>),
    //VInteger(u64),
    //...
}

#[derive(Debug, PartialEq)]
pub struct PObjectRef<'a> {
    pub name: CompleteStr<'a>,
    pub parameters: Vec<PValue<'a>>,
}

#[derive(Debug, PartialEq)]
pub enum PStatement<'a> {
    Comment(PComment<'a>),
    StateCall(PObjectRef<'a>, CompleteStr<'a>, Vec<PValue<'a>>),
    // TODO object instance, variable definition, case, exception
}

#[derive(Debug, PartialEq)]
pub struct PStateDef<'a> {
    pub name: CompleteStr<'a>,
    pub object_name: CompleteStr<'a>,
    pub parameters: Vec<PParameter<'a>>,
    pub statements: Vec<PStatement<'a>>,
}

#[derive(Debug, PartialEq)]
pub enum PDeclaration<'a> {
    Comment(PComment<'a>),
    Metadata(PMetadata<'a>),
    Object(PObjectDef<'a>),
    State(PStateDef<'a>),
}

#[derive(Debug, PartialEq)]
pub struct PCode<'a> {
    header: PHeader,
    code: Vec<PDeclaration<'a>>,
}

// PARSERS adapter for str instead of byte{]
//

// eat char separators instead of u8
named!(space_s<CompleteStr,CompleteStr>, eat_separator!(&" \t\r\n"[..]));

// ws! for chars instead of u8
macro_rules! sp (
  ($i:expr, $($args:tt)*) => (
    {
      sep!($i, space_s, $($args)*)
    }
  )
);

// PARSERS
//

named!(pub header<CompleteStr,PHeader>,
  do_parse!(
    opt!(preceded!(tag!("#!/"),take_until_and_consume!("\n"))) >>
    // strict parser so that this does not diverge and anything can read the line
    tag!("@format=") >>
    version: map_res!(take_until!("\n"), |s:CompleteStr| s.parse::<u32>()) >>
    tag!("\n") >>
    (PHeader { version })
  )
);

// string
// TODO escaped string
named!(delimited_string<CompleteStr,CompleteStr>,
  delimited!(char!('"'),
             take_until!("\""),
             char!('"')
  )
);

// Value
//
named!(typed_value<CompleteStr,PValue>,
    // TODO other types
    alt!(delimited_string => { |x| PValue::VString(x) })
);

// comments
named!(comment_line<CompleteStr,CompleteStr>,
  preceded!(tag!("#"),
            alt!(take_until_and_consume!("\n")
                |rest
            )
  )
);
named!(comment_block<CompleteStr,PComment>,
  // complete is mandatory to manage end of file with many
  many0!(comment_line)
);

// metadata
named!(metadata<CompleteStr,PMetadata>,
  sp!(do_parse!(char!('@') >>
    key: alphanumeric >>
    char!('=') >>
    value: typed_value >>
    (PMetadata {key, value})
  ))
);

// type
named!(typename<CompleteStr,PType>,
  alt!(
    tag!("string")      => { |_| PType::TString }  |
    tag!("int")         => { |_| PType::TInteger } |
    tag!("struct")      => { |_| PType::TStruct }  |
    tag!("list")        => { |_| PType::TList }
  )
);

// Parameters
named!(parameter<CompleteStr,PParameter>,
  sp!(do_parse!(
    ptype: opt!(sp!(terminated!(typename, char!(':')))) >>
    name: alphanumeric >>
    (PParameter {ptype, name})
  ))
);

// ObjectDef
named!(object_def<CompleteStr,PObjectDef>,
  sp!(do_parse!(
    tag!("ObjectDef") >>
    name: alphanumeric >>
    tag!("(") >>
    parameters: separated_list!(
        tag!(","),
        parameter) >>
    tag!(")") >>
    (PObjectDef {name, parameters})
  ))
);

// ObjectRef
named!(object_ref<CompleteStr,PObjectRef>,
  sp!(do_parse!(
    name: alphanumeric >>
    // TODO accept no parameter with no ()
    tag!("(") >>
    parameters: separated_list!(
        tag!(","),
        typed_value) >>
    tag!(")") >>
    (PObjectRef {name, parameters})
  ))
);

// statements
named!(statement<CompleteStr,PStatement>,
  alt!(
      // state call
      sp!(do_parse!(
          object: object_ref >>
          tag!(".") >>
          state: alphanumeric >>
          tag!("(") >>
          parameters: separated_list!(
              tag!(","),
              typed_value) >>
          tag!(")") >>
          (PStatement::StateCall(object,state,parameters))
      ))
    | comment_block => { |x| PStatement::Comment(x) }
  )
);

// state definition
named!(state<CompleteStr,PStateDef>,
  sp!(do_parse!(
      object_name: alphanumeric >>
      tag!("state") >>
      name: alphanumeric >>
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
named!(declaration<CompleteStr,PDeclaration>,
    sp!(alt!(
          state         => { |x| PDeclaration::State(x) }
        | object_def    => { |x| PDeclaration::Object(x) }
        | metadata      => { |x| PDeclaration::Metadata(x) }
        | comment_block => { |x| PDeclaration::Comment(x) }
      ))
);

//pub fn parse<'a>(input: CompleteStr<'a>) -> IResult<CompleteStr<'a>, PCode<'a>>
//{
//   let header = try!(header(input));
//   if header.version != 0 {
//       return Err(Err::Failure);
//   }
//
//}
named!(pub code<CompleteStr,Vec<PDeclaration>>,
  many0!(declaration)
);

// TESTS
//

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_comment_line() {
        assert_eq!(
            comment_line(CompleteStr("#hello Herman\n")),
            Ok((CompleteStr(""), CompleteStr("hello Herman")))
        );
        assert_eq!(
            comment_line(CompleteStr("#hello Herman\nHola")),
            Ok((CompleteStr("Hola"), CompleteStr("hello Herman")))
        );
        assert_eq!(
            comment_line(CompleteStr("#hello Herman!")),
            Ok((CompleteStr(""), CompleteStr("hello Herman!")))
        );
        assert_eq!(
            comment_line(CompleteStr("#hello\nHerman\n")),
            Ok((CompleteStr("Herman\n"), CompleteStr("hello")))
        );
        assert!(comment_line(CompleteStr("hello\nHerman\n")).is_err());
    }

    #[test]
    fn test_comment_block() {
        assert_eq!(
            comment_block(CompleteStr("#hello Herman\n")),
            Ok((CompleteStr(""), vec![CompleteStr("hello Herman")]))
        );
        assert_eq!(
            comment_block(CompleteStr("#hello Herman!")),
            Ok((CompleteStr(""), vec![CompleteStr("hello Herman!")]))
        );
        assert_eq!(
            comment_block(CompleteStr("#hello\n#Herman\n")),
            Ok((
                CompleteStr(""),
                vec![CompleteStr("hello"), CompleteStr("Herman")]
            ))
        );
        assert_eq!(
            comment_block(CompleteStr("")),
            Ok((CompleteStr(""), vec![]))
        );
        assert_eq!(
            comment_block(CompleteStr("hello Herman")),
            Ok((CompleteStr("hello Herman"), vec![]))
        );
    }

    #[test]
    fn test_delimited_string() {
        assert_eq!(
            delimited_string(CompleteStr("\"hello Herman\"")),
            Ok((CompleteStr(""), CompleteStr("hello Herman")))
        );
        assert!(delimited_string(CompleteStr("hello Herman")).is_err());
    }

    #[test]
    fn test_metadata() {
        assert_eq!(
            metadata(CompleteStr("@key=\"value\"")),
            Ok((
                CompleteStr(""),
                PMetadata {
                    key: CompleteStr("key"),
                    value: PValue::VString(CompleteStr("value"))
                }
            ))
        );
        assert_eq!(
            metadata(CompleteStr("@key = \"value\"")),
            Ok((
                CompleteStr(""),
                PMetadata {
                    key: CompleteStr("key"),
                    value: PValue::VString(CompleteStr("value"))
                }
            ))
        );
    }

    #[test]
    fn test_alphanumeric() {
        assert_eq!(
            alphanumeric(CompleteStr("simple ")),
            Ok((CompleteStr(" "), CompleteStr("simple")))
        );
        assert_eq!(
            alphanumeric(CompleteStr("simple?")),
            Ok((CompleteStr("?"), CompleteStr("simple")))
        );
        assert_eq!(
            alphanumeric(CompleteStr("5impl3 ")),
            Ok((CompleteStr(" "), CompleteStr("5impl3")))
        );
        assert!(alphanumeric(CompleteStr("%imple ")).is_err());
    }

    #[test]
    fn test_parameter() {
        assert_eq!(
            parameter(CompleteStr("hello ")),
            Ok((
                CompleteStr(" "),
                PParameter {
                    name: CompleteStr("hello"),
                    ptype: None
                }
            ))
        );
        assert_eq!(
            parameter(CompleteStr("string:hello ")),
            Ok((
                CompleteStr(" "),
                PParameter {
                    name: CompleteStr("hello"),
                    ptype: Some(PType::TString)
                }
            ))
        );
        assert_eq!(
            parameter(CompleteStr(" string : hello ")),
            Ok((
                CompleteStr(" "),
                PParameter {
                    name: CompleteStr("hello"),
                    ptype: Some(PType::TString)
                }
            ))
        );
    }

    #[test]
    fn test_object_def() {
        assert_eq!(
            object_def(CompleteStr("ObjectDef hello()")),
            Ok((
                CompleteStr(""),
                PObjectDef {
                    name: CompleteStr("hello"),
                    parameters: vec![]
                }
            ))
        );
        assert_eq!(
            object_def(CompleteStr("ObjectDef  hello2 ( )")),
            Ok((
                CompleteStr(""),
                PObjectDef {
                    name: CompleteStr("hello2"),
                    parameters: vec![]
                }
            ))
        );
        assert_eq!(
            object_def(CompleteStr("ObjectDef hello (string: p1, p2)")),
            Ok((
                CompleteStr(""),
                PObjectDef {
                    name: CompleteStr("hello"),
                    parameters: vec![
                        PParameter {
                            name: CompleteStr("p1"),
                            ptype: Some(PType::TString)
                        },
                        PParameter {
                            name: CompleteStr("p2"),
                            ptype: None
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
            typed_value(CompleteStr("\"This is a string\"")),
            Ok((
                CompleteStr(""),
                PValue::VString(CompleteStr("This is a string"))
            ))
        );
    }

    #[test]
    fn test_headers() {
        assert_eq!(
            header(CompleteStr("#!/bin/bash\n@format=1\n")),
            Ok((CompleteStr(""), PHeader { version: 1 }))
        );
        assert_eq!(
            header(CompleteStr("@format=21\n")),
            Ok((CompleteStr(""), PHeader { version: 21 }))
        );
        assert!(header(CompleteStr("@format=21.5\n")).is_err());
    }

    #[test]
    fn test_object_ref() {
        assert_eq!(
            object_ref(CompleteStr("hello()")),
            Ok((
                CompleteStr(""),
                PObjectRef {
                    name: CompleteStr("hello"),
                    parameters: vec![]
                }
            ))
        );
        assert_eq!(
            object_ref(CompleteStr("hello2 ( )")),
            Ok((
                CompleteStr(""),
                PObjectRef {
                    name: CompleteStr("hello2"),
                    parameters: vec![]
                }
            ))
        );
        assert_eq!(
            object_ref(CompleteStr("hello ( \"p1\", \"p2\" )")),
            Ok((
                CompleteStr(""),
                PObjectRef {
                    name: CompleteStr("hello"),
                    parameters: vec![
                        PValue::VString(CompleteStr("p1")),
                        PValue::VString(CompleteStr("p2"))
                    ]
                }
            ))
        );
    }

    #[test]
    fn test_statement() {
        assert_eq!(
            statement(CompleteStr("object().state()")),
            Ok((
                CompleteStr(""),
                PStatement::StateCall(
                    PObjectRef {
                        name: CompleteStr("object"),
                        parameters: vec![]
                    },
                    CompleteStr("state"),
                    vec![]
                )
            ))
        );
        assert_eq!(
            statement(CompleteStr("object().state( \"p1\", \"p2\")")),
            Ok((
                CompleteStr(""),
                PStatement::StateCall(
                    PObjectRef {
                        name: CompleteStr("object"),
                        parameters: vec![]
                    },
                    CompleteStr("state"),
                    vec![
                        PValue::VString(CompleteStr("p1")),
                        PValue::VString(CompleteStr("p2"))
                    ]
                )
            ))
        );
        assert_eq!(
            statement(CompleteStr("#hello Herman\n")),
            Ok((
                CompleteStr(""),
                PStatement::Comment(vec![CompleteStr("hello Herman")])
            ))
        );
    }

    #[test]
    fn test_state() {
        assert_eq!(
            state(CompleteStr("object state configuration() {}")),
            Ok((
                CompleteStr(""),
                PStateDef {
                    name: CompleteStr("configuration"),
                    object_name: CompleteStr("object"),
                    parameters: vec![],
                    statements: vec![]
                }
            ))
        );
    }
}
