use nom::rest;
use nom::alphanumeric;


// STRUCTURES 
//
type PComment<'a> = Vec<&'a str>;

#[derive(Debug,PartialEq)]
pub struct PMetadata<'a> {
    pub key: &'a str,
    // TODO replace with a structure
    pub value: &'a str, 
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

// string
// TODO escaped string
named!(delimited_string<&str,&str>,
  delimited!(char!('"'),
             take_until!("\""),
             char!('"')
  )
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
    value: delimited_string >>
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
        assert_eq!(metadata("@key=\"value\""), Ok(("", PMetadata { key:"key", value:"value" })));
        assert_eq!(metadata("@key = \"value\""), Ok(("", PMetadata { key:"key", value:"value" })));
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
}
