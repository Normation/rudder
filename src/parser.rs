use nom::*;

// STRUCTURES
//

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
    pub name: &'a str,
    pub parameters: Vec<PParameter<'a>>,
}

#[derive(Debug, PartialEq)]
pub enum PValue<'a> {
    VString(&'a str),
    //VInteger(u64),
    //...
}

#[derive(Debug, PartialEq)]
pub struct PObjectRef<'a> {
    pub name: &'a str,
    pub parameters: Vec<PValue<'a>>,
}

#[derive(Debug, PartialEq)]
pub enum PStatement<'a> {
    StateCall(PObjectRef<'a>, &'a str, Vec<PValue<'a>>),
    // TODO object instance, variable definition, case, exception
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
}

#[derive(Debug, PartialEq)]
pub struct PCode<'a> {
    header: PHeader,
    code: Vec<PDeclaration<'a>>,
}

// PARSERS adapter for str instead of byte{]
//

// eat char separators instead of u8
named!(space_s<&str,&str>, eat_separator!(&" \t\r\n"[..]));

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

named!(pub header<&str,PHeader>,
  do_parse!(
    opt!(preceded!(tag!("#!/"),take_until_and_consume!("\n"))) >>
    // strict parser so that this does not diverge and anything can read the line
    tag!("@format=") >>
    version: map_res!(take_until!("\n"), |s:&str| s.parse::<u32>()) >>
    tag!("\n") >>
    (PHeader { version })
  )
);

// string
// TODO escaped string
named!(delimited_string<&str,&str>,
  delimited!(char!('"'),
             take_until!("\""),
             char!('"')
  )
);

// Value
//
named!(typed_value<&str,PValue>,
    // TODO other types
    alt!(delimited_string => { |x| PValue::VString(x) })
);

// comments
named!(comment_line<&str,&str>,
  preceded!(tag!("#"),
            alt!(complete!(take_until_and_consume!("\n")) 
                |rest
            )
  )
);
named!(comment_block<&str,PComment>,
  // complete is mandatory to manage end of file with many
  many0!(complete!(comment_line))
);

// metadata
named!(metadata<&str,PMetadata>,
  sp!(do_parse!(char!('@') >>
    key: alphanumeric >>
    char!('=') >>
    value: typed_value >>
    (PMetadata {key, value})
  ))
);

// type
named!(typename<&str,PType>,
  alt_complete!(
    tag!("string")      => { |_| PType::TString }  |
    tag!("int")         => { |_| PType::TInteger } |
    tag!("struct")      => { |_| PType::TStruct }  |
    tag!("list")        => { |_| PType::TList }
  )
);

// Parameters
named!(parameter<&str,PParameter>,
  sp!(do_parse!(
    ptype: opt!(sp!(terminated!(typename, char!(':')))) >>
    name: alphanumeric >>
    (PParameter {ptype, name})
  ))
);

// ObjectDef
named!(object_def<&str,PObjectDef>,
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
named!(object_ref<&str,PObjectRef>,
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
named!(statement<&str,PStatement>,
  alt!(
      // sate call
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
  )
);

// state definition
named!(state<&str,PStateDef>,
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
named!(declaration<&str,PDeclaration>,
    complete!(sp!(alt!(
          state         => { |x| PDeclaration::State(x) }
        | object_def    => { |x| PDeclaration::Object(x) }
        | metadata      => { |x| PDeclaration::Metadata(x) }
        | comment_block => { |x| PDeclaration::Comment(x) }
      )))
);

//pub fn parse<'a>(input: &'a str) -> IResult<&'a str, PCode<'a>>
//{
//   let header = try!(header(input));
//   if header.version != 0 {
//       return Err(Err::Failure);
//   }
//   
//}
named!(pub code<&str,Vec<PDeclaration>>,
  many0!(declaration)
);

// TESTS
//

// We don't want to have and write CompleteStr everywhere
// Since space characters are often meaningless, we use one when needed
// As a consequence, the parser caller muse make sure there is always a trailing space or newline
// in the data to be parsed
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_comment_line() {
        assert_eq!(comment_line("#hello Herman\n"), Ok(("", "hello Herman")));
        assert_eq!(
            comment_line("#hello Herman\nHola"),
            Ok(("Hola", "hello Herman"))
        );
        assert_eq!(comment_line("#hello Herman!"), Ok(("", "hello Herman!")));
        assert_eq!(comment_line("#hello\nHerman\n"), Ok(("Herman\n", "hello")));
        assert!(comment_line("hello\nHerman\n").is_err());
    }

    #[test]
    fn test_comment_block() {
        assert_eq!(
            comment_block("#hello Herman\n"),
            Ok(("", vec!["hello Herman"]))
        );
        assert_eq!(
            comment_block("#hello Herman!"),
            Ok(("", vec!["hello Herman!"]))
        );
        assert_eq!(
            comment_block("#hello\n#Herman\n"),
            Ok(("", vec!["hello", "Herman"]))
        );
        assert_eq!(comment_block(""), Ok(("", vec![])));
        assert_eq!(comment_block("hello Herman"), Ok(("hello Herman", vec![])));
    }

    #[test]
    fn test_delimited_string() {
        assert_eq!(
            delimited_string("\"hello Herman\""),
            Ok(("", "hello Herman"))
        );
        assert!(delimited_string("hello Herman").is_err());
    }

    #[test]
    fn test_metadata() {
        assert_eq!(
            metadata("@key=\"value\""),
            Ok((
                "",
                PMetadata {
                    key: "key",
                    value: PValue::VString("value")
                }
            ))
        );
        assert_eq!(
            metadata("@key = \"value\""),
            Ok((
                "",
                PMetadata {
                    key: "key",
                    value: PValue::VString("value")
                }
            ))
        );
    }

    #[test]
    fn test_alphanumeric() {
        assert_eq!(alphanumeric("simple "), Ok((" ", "simple")));
        assert_eq!(alphanumeric("simple?"), Ok(("?", "simple")));
        assert_eq!(alphanumeric("5impl3 "), Ok((" ", "5impl3")));
        assert!(alphanumeric("%imple ").is_err());
    }

    #[test]
    fn test_parameter() {
        assert_eq!(
            parameter("hello "),
            Ok((
                " ",
                PParameter {
                    name: "hello",
                    ptype: None
                }
            ))
        );
        assert_eq!(
            parameter("string:hello "),
            Ok((
                " ",
                PParameter {
                    name: "hello",
                    ptype: Some(PType::TString)
                }
            ))
        );
        assert_eq!(
            parameter(" string : hello "),
            Ok((
                " ",
                PParameter {
                    name: "hello",
                    ptype: Some(PType::TString)
                }
            ))
        );
    }

    #[test]
    fn test_object_def() {
        assert_eq!(
            object_def("ObjectDef hello()"),
            Ok((
                "",
                PObjectDef {
                    name: "hello",
                    parameters: vec![]
                }
            ))
        );
        assert_eq!(
            object_def("ObjectDef  hello2 ( )"),
            Ok((
                "",
                PObjectDef {
                    name: "hello2",
                    parameters: vec![]
                }
            ))
        );
        assert_eq!(
            object_def("ObjectDef hello (string: p1, p2)"),
            Ok((
                "",
                PObjectDef {
                    name: "hello",
                    parameters: vec![
                        PParameter {
                            name: "p1",
                            ptype: Some(PType::TString)
                        },
                        PParameter {
                            name: "p2",
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
            typed_value("\"This is a string\""),
            Ok(("", PValue::VString("This is a string")))
        );
    }

    #[test]
    fn test_headers() {
        assert_eq!(
            header("#!/bin/bash\n@format=1\n"),
            Ok(("", PHeader { version: 1 }))
        );
        assert_eq!(header("@format=21\n"), Ok(("", PHeader { version: 21 })));
        assert!(header("@format=21.5\n").is_err());
    }

    #[test]
    fn test_object_ref() {
        assert_eq!(
            object_ref("hello()"),
            Ok((
                "",
                PObjectRef {
                    name: "hello",
                    parameters: vec![]
                }
            ))
        );
        assert_eq!(
            object_ref("hello2 ( )"),
            Ok((
                "",
                PObjectRef {
                    name: "hello2",
                    parameters: vec![]
                }
            ))
        );
        assert_eq!(
            object_ref("hello ( \"p1\", \"p2\" )"),
            Ok((
                "",
                PObjectRef {
                    name: "hello",
                    parameters: vec![PValue::VString("p1"), PValue::VString("p2")]
                }
            ))
        );
    }

    #[test]
    fn test_statement() {
        assert_eq!(
            statement("object().state()"),
            Ok((
                "",
                PStatement::StateCall(
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
            statement("object().state( \"p1\", \"p2\")"),
            Ok((
                "",
                PStatement::StateCall(
                    PObjectRef {
                        name: "object",
                        parameters: vec![]
                    },
                    "state",
                    vec![PValue::VString("p1"), PValue::VString("p2")]
                )
            ))
        );
    }

    #[test]
    fn test_state() {
        assert_eq!(
            state("object state configuration() { }"),
            Ok((
                "",
                PStateDef { name: "configuration", object_name: "object", parameters: vec![], statements: vec![] }
                ))
            );
    }
}
