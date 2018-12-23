use nom::rest;
use nom::alphanumeric;


// STRUCTURES 
//

#[derive(Debug,PartialEq)]
pub struct PHeader {
    pub version: u32,
}

type PComment<'a> = Vec<&'a str>;

#[derive(Debug,PartialEq)]
pub struct PMetadata<'a> {
    pub key: &'a str,
    pub value: PValue<'a>, 
}

#[derive(Debug,PartialEq)]
pub struct PParameter<'a> {
    pub name: &'a str,
    // TODO store default value in type
    pub ptype: Option<PType>,
}

#[derive(Debug,PartialEq)]
pub enum PType {
    TString,
    TInteger,
    TStruct,
    TList,
}

#[derive(Debug,PartialEq)]
pub struct PObjectType<'a> {
    pub name: &'a str,
    pub parameters: Vec<PParameter<'a>>,
}

#[derive(Debug,PartialEq)]
pub enum PValue<'a> {
    VString(&'a str),
    //VInteger(u64),
    // Composite types TODO
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

named!(header<&str,PHeader>,
  do_parse!(
    opt!(preceded!(tag!("#!/"),take_until_and_consume!("\n"))) >>
    // Very strict parser so that anything can read this
    tag!("@version=") >>
    version: take_until!("\n") >>
    tag!("\n") >>
    // TODO replace unwrap
    (PHeader { version: version.parse().unwrap() })
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
named!(pub comment_line<&str,&str>,
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

// ObjectType
named!(object_type<&str,PObjectType>,
  sp!(do_parse!(
    tag!("ObjectType") >>
    name: alphanumeric >>
    tag!("(") >>
    parameters: separated_list!(
        tag!(","),
        parameter) >>
    tag!(")") >>
    (PObjectType {name, parameters})
  ))
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
        assert_eq!(comment_line("#hello Herman\n"), Ok(("","hello Herman")));
        assert_eq!(comment_line("#hello Herman\nHola"), Ok(("Hola","hello Herman")));
        assert_eq!(comment_line("#hello Herman!"), Ok(("","hello Herman!")));
        assert_eq!(comment_line("#hello\nHerman\n"), Ok(("Herman\n","hello")));
        assert!(comment_line("hello\nHerman\n").is_err());
    }

    #[test]
    fn test_comment_block() {
        assert_eq!(comment_block("#hello Herman\n"), Ok(("",vec!["hello Herman"])));
        assert_eq!(comment_block("#hello Herman!"), Ok(("",vec!["hello Herman!"])));
        assert_eq!(comment_block("#hello\n#Herman\n"), Ok(("",vec!["hello","Herman"])));
        assert_eq!(comment_block(""), Ok(("",vec![])));
        assert_eq!(comment_block("hello Herman"), Ok(("hello Herman",vec![])));
    }

    #[test]
    fn test_delimited_string() {
        assert_eq!(delimited_string("\"hello Herman\""), Ok(("", "hello Herman")));
        assert!(delimited_string("hello Herman").is_err());
    }

    #[test]
    fn test_metadata() {
        assert_eq!(metadata("@key=\"value\""), Ok(("", PMetadata { key:"key", value:PValue::VString("value") })));
        assert_eq!(metadata("@key = \"value\""), Ok(("", PMetadata { key:"key", value:PValue::VString("value") })));
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
        assert_eq!(parameter("hello "), Ok((" ", PParameter { name: "hello", ptype: None })));
        assert_eq!(parameter("string:hello "), Ok((" ", PParameter { name: "hello", ptype: Some(PType::TString) })));
        assert_eq!(parameter(" string : hello "), Ok((" ", PParameter { name: "hello", ptype: Some(PType::TString) })));
    }

    #[test]
    fn test_object_type() {
        assert_eq!(object_type("ObjectType hello()"), Ok(("", PObjectType { name: "hello", parameters: vec![] })));
        assert_eq!(object_type("ObjectType  hello2 ( )"), Ok(("", PObjectType { name: "hello2", parameters: vec![] })));
        assert_eq!(object_type("ObjectType hello (string: p1, p2)"), Ok(("", PObjectType { name: "hello", 
                                                                                parameters: vec![ PParameter { name: "p1", ptype: Some(PType::TString) },
                                                                                                  PParameter { name: "p2", ptype: None }] })));
    }

    #[test]
    fn test_value() {
        // TODO other types
        assert_eq!(typed_value("\"This is a string\""), Ok(("", PValue::VString("This is a string"))));
    }

    #[test]
    fn test_headers() {
        assert_eq!(header("#!/bin/bash\n@version=1\n"), Ok(("", PHeader { version: 1})));
        assert_eq!(header("@version=21\n"), Ok(("", PHeader { version: 21})));
        //TODO
        //assert!(header("@version=21.5\n").is_err());
    }
}
