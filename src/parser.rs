use nom::types::CompleteStr;
use nom::*;
//use nom_locate::position;
use nom_locate::LocatedSpan;
use std::fmt;
use std::hash::{Hash, Hasher};
use std::ops::Deref;

// TODO Error management
// TODO add more types
// TODO lifetime = 'src
// TODO store position in strings
// TODO tests for high level parsers
// TODO _ in identifiers

// STRUCTURES
//

// All input are Located Complete str
pub type PInput<'a> = LocatedSpan<CompleteStr<'a>, &'a str>;

// convenient creator
pub fn pinput<'a>(name: &'a str, input: &'a str) -> PInput<'a> {
    LocatedSpan::new(CompleteStr(input), name)
}

// All output are token based, they must behave like &str
// Copy for convenient use
#[derive(Debug, Copy, Clone)]
pub struct PToken<'a> {
    val: LocatedSpan<CompleteStr<'a>, &'a str>,
}

// Create from strings
impl<'a> PToken<'a> {
    fn new(name: &'a str, input: &'a str) -> Self {
        PToken {
            val: LocatedSpan::new(CompleteStr(input), name),
        }
    }
    pub fn position_str(&self) -> String {
        let (file, line, col) = self.position();
        format!("{}:{}:{}", file, line, col)
    }
    pub fn position(&self) -> (String, u32, usize) {
        (
            self.val.extra.to_string(),
            self.val.line,
            self.val.get_utf8_column(),
        )
    }
    pub fn fragment(&self) -> &'a str {
        &self.val.fragment
    }
}
// Convert from str (lossy, used for terse tests)
impl<'a> From<&'a str> for PToken<'a> {
    fn from(input: &'a str) -> Self {
        PToken {
            val: LocatedSpan::new(CompleteStr(input), ""),
        }
    }
}
// Convert from PInput
impl<'a> From<PInput<'a>> for PToken<'a> {
    fn from(val: PInput<'a>) -> Self {
        PToken { val }
    }
}
// PartialEq used by tests and by Token users
impl<'a> PartialEq for PToken<'a> {
    fn eq(&self, other: &PToken) -> bool {
        self.val.fragment == other.val.fragment
    }
}
// Eq used by Token users for HAshMaps (nothing to do)
impl<'a> Eq for PToken<'a> {}
// Hash used by Token users as keys in HashMaps
impl<'a> Hash for PToken<'a> {
    fn hash<H: Hasher>(&self, state: &mut H) {
        self.val.fragment.hash(state);
    }
}
// Display for debug info
impl<'a> fmt::Display for PToken<'a> {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(f, "'{}' at {}", self.val.fragment, self.position_str())
    }
}
// TODO Deref or AsRef ?
//// Deref to &str
impl<'a> Deref for PToken<'a> {
    type Target = &'a str;
    fn deref(&self) -> &&'a str {
        &self.val.fragment
    }
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

type PComment<'a> = Vec<PToken<'a>>;

#[derive(Debug, PartialEq)]
pub struct PMetadata<'a> {
    pub key: PToken<'a>,
    pub value: PValue<'a>,
}

#[derive(Debug, PartialEq)]
pub struct PParameter<'a> {
    pub name: PToken<'a>,
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
    pub global: bool,
    pub name: PToken<'a>,
    pub items: Vec<PToken<'a>>,
}

#[derive(Debug, PartialEq)]
pub struct PEnumMapping<'a> {
    pub from: PToken<'a>,
    pub to: PToken<'a>,
    pub mapping: Vec<(PToken<'a>, PToken<'a>)>,
}

#[derive(Debug, PartialEq)]
pub enum PEnumExpression<'a> {
    //       variable        enum              value
    Compare(Option<PToken<'a>>, Option<PToken<'a>>, PToken<'a>),
    And(Box<PEnumExpression<'a>>, Box<PEnumExpression<'a>>),
    Or(Box<PEnumExpression<'a>>, Box<PEnumExpression<'a>>),
    Not(Box<PEnumExpression<'a>>),
    Default,
}

#[derive(Debug, PartialEq)]
pub struct PResourceDef<'a> {
    pub name: PToken<'a>,
    pub parameters: Vec<PParameter<'a>>,
}

#[derive(Debug, PartialEq)]
pub enum PValue<'a> {
    String(String),
    // to make sure we have a reference in this struct because there will be one some day
    #[allow(dead_code)]
    XX(PToken<'a>),
    //VInteger(u64),
    //...
}

#[derive(Debug, PartialEq)]
pub struct PResourceRef<'a> {
    pub name: PToken<'a>,
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
        Option<PToken<'a>>, // outcome
        PCallMode,          // mode
        PResourceRef<'a>,   // resource
        PToken<'a>,         // state name
        Vec<PValue<'a>>,    // parameters
    ),
    //   list of condition          then
    Case(Vec<(PEnumExpression<'a>, Box<PStatement<'a>>)>),
    // Stop engine
    Fail(PToken<'a>),
    // Inform the user of something
    Log(PToken<'a>),
    // Return a specific outcome
    Return(PToken<'a>),
    // Do nothing
    Noop,
    // TODO condition instance, resource instance, variable definition
}

#[derive(Debug, PartialEq)]
pub struct PStateDef<'a> {
    pub name: PToken<'a>,
    pub resource_name: PToken<'a>,
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

named!(header<PInput,PHeader>,
    do_parse!(
        opt!(preceded!(tag!("#!/"),take_until_and_consume!("\n"))) >>
        // strict parser so that this does not diverge and anything can read the line
        fail!(Error::InvalidFormat,tag!("@format=")) >>
        version: fail!(Error::InvalidFormat,map_res!(take_until!("\n"), |s:PInput| s.fragment.parse::<u32>())) >>
        fail!(Error::InvalidFormat,tag!("\n")) >>
        (PHeader { version })
    )
);

named!(pub identifier<PInput,PToken>,
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
        fail!(Error::UnterminatedString,tag!("\""))
    )
);

named!(unescaped_string<PInput,String>,
    delimited!(
        // TODO string containing """
        tag!("\"\"\""),
        map!(take_until!("\"\"\""), { |x:PInput| x.to_string() }),
        fail!(Error::UnterminatedString,tag!("\"\"\""))
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

// Enums
named!(pub penum<PInput,PEnum>,
    sp!(do_parse!(
        global: opt!(tag!("global")) >>
        tag!("enum") >>
        name: identifier >>
        tag!("{") >>
        items: sp!(separated_list!(tag!(","), identifier)) >>
        tag!("}") >>
        (PEnum {global: global.is_some(), name, items})
    ))
);

named!(pub enum_mapping<PInput,PEnumMapping>,
    sp!(do_parse!(
        tag!("enum") >>
        from: identifier >>
        tag!("~>") >>
        to: identifier >>
        tag!("{") >>
        mapping: sp!(separated_list!(
                tag!(","),
                separated_pair!(
                    alt!(identifier|map!(tag!("*"),|x| x.into())),
                    tag!("->"),
                    alt!(identifier|map!(tag!("*"),|x| x.into())))
            )) >>
        tag!("}") >>
        (PEnumMapping {from, to, mapping})
    ))
);

named!(pub enum_expression<PInput,PEnumExpression>,
    alt!(enum_or_expression
       | enum_and_expression
       | enum_not_expression
       | enum_atom
       | tag!("default") => { |_| PEnumExpression::Default }
    )
);

named!(enum_atom<PInput,PEnumExpression>,
    sp!(alt!(
        delimited!(tag!("("),enum_expression,tag!(")"))
      | do_parse!(
            var: identifier >>
            tag!("=~") >>
            penum: opt!(terminated!(identifier,tag!(":"))) >>
            value: identifier >>
            (PEnumExpression::Compare(Some(var),penum,value))
        )
      | do_parse!(
            var: identifier >>
            tag!("!~") >>
            penum: opt!(terminated!(identifier,tag!(":"))) >>
            value: identifier >>
            (PEnumExpression::Not(Box::new(PEnumExpression::Compare(Some(var),penum,value))))
        )
      | do_parse!(
            penum: opt!(terminated!(identifier,tag!(":"))) >>
            value: identifier >>
            (PEnumExpression::Compare(None,penum,value))
        )
    ))
);

named!(enum_or_expression<PInput,PEnumExpression>,
    sp!(do_parse!(
        left: alt!(enum_and_expression | enum_not_expression | enum_atom) >>
        tag!("||") >>
        right: alt!(enum_or_expression | enum_and_expression | enum_not_expression | enum_atom) >>
        (PEnumExpression::Or(Box::new(left),Box::new(right)))
    ))
);

named!(enum_and_expression<PInput,PEnumExpression>,
    sp!(do_parse!(
        left: alt!(enum_not_expression | enum_atom) >>
        tag!("&&") >>
        right: alt!(enum_and_expression | enum_not_expression | enum_atom) >>
        (PEnumExpression::And(Box::new(left),Box::new(right)))
    ))
);

named!(enum_not_expression<PInput,PEnumExpression>,
    sp!(do_parse!(
        tag!("!") >>
        right: enum_atom >>
        (PEnumExpression::Not(Box::new(right)))
    ))
);

// comments
named!(comment_line<PInput,PToken>,
    map!(
        preceded!(tag!("##"),
            alt!(take_until_and_consume!("\n")
                |rest
            )
        ),
        |x| x.into()
    )
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

// ResourceDef
named!(resource_def<PInput,PResourceDef>,
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
named!(resource_ref<PInput,PResourceRef>,
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
          (PStatement::Case( vec![(expr,Box::new(stmt)), (PEnumExpression::Default,Box::new(PStatement::Log(PToken::new("","TODO"))))] ))
      ))
      // Flow statements
    | tag!("fail!")    => { |_| PStatement::Fail(PToken::new("","TODO")) } // TODO proper message
    | tag!("return!!") => { |_| PStatement::Return(PToken::new("","TODO")) } // TODO proper message
    | tag!("log!")     => { |_| PStatement::Log(PToken::new("","TODO")) } // TODO proper message
    | tag!("noop!")    => { |_| PStatement::Noop }
  )
);

// state definition
named!(state<PInput,PStateDef>,
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
named!(declaration<PInput,PDeclaration>,
    sp!(alt_complete!(
          resource_def    => { |x| PDeclaration::Resource(x) }
        | metadata      => { |x| PDeclaration::Metadata(x) }
        | state         => { |x| PDeclaration::State(x) }
        | comment_block => { |x| PDeclaration::Comment(x) }
        | penum         => { |x| PDeclaration::Enum(x) }
        | enum_mapping  => { |x| PDeclaration::Mapping(x) }
      ))
);

named!(pub parse<PInput,PFile>,
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
        r: Result<(PInput<'a>, O), Err<PInput<'a>, E>>,
    ) -> Result<(&str, O), Err<PInput<'a>, E>> {
        match r {
            Ok((x, y)) => Ok((*x.fragment, y)),
            Err(e) => Err(e),
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
            mapok(enum_mapping(pinput(
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
            mapok(enum_mapping(pinput(
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
    fn test_enum_expression() {
        assert_eq!(
            mapok(enum_expression(pinput("", "a=~b:c"))),
            Ok((
                "",
                PEnumExpression::Compare(Some("a".into()), Some("b".into()), "c".into())
            ))
        );
        assert_eq!(
            mapok(enum_expression(pinput("", "a=~bc"))),
            Ok((
                "",
                PEnumExpression::Compare(Some("a".into()), None, "bc".into())
            ))
        );
        assert_eq!(
            mapok(enum_expression(pinput("", "bc"))),
            Ok(("", PEnumExpression::Compare(None, None, "bc".into())))
        );
        assert_eq!(
            mapok(enum_expression(pinput("", "(a =~ b:hello)"))),
            Ok((
                "",
                PEnumExpression::Compare(Some("a".into()), Some("b".into()), "hello".into())
            ))
        );
        assert_eq!(
            mapok(enum_expression(pinput("", "(a !~ b:hello)"))),
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
            mapok(enum_expression(pinput("", "bc&&(a||b=~hello:g)"))),
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
            mapok(enum_expression(pinput("", "! a =~ hello"))),
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
    fn test_comment_line() {
        assert_eq!(
            mapok(comment_line(pinput("", "##hello Herman\n"))),
            Ok(("", "hello Herman".into()))
        );
        assert_eq!(
            mapok(comment_line(pinput("", "##hello Herman\nHola"))),
            Ok(("Hola", "hello Herman".into()))
        );
        assert_eq!(
            mapok(comment_line(pinput("", "##hello Herman!"))),
            Ok(("", "hello Herman!".into()))
        );
        assert_eq!(
            mapok(comment_line(pinput("", "##hello\nHerman\n"))),
            Ok(("Herman\n", "hello".into()))
        );
        assert!(comment_line(pinput("", "hello\nHerman\n")).is_err());
    }

    #[test]
    fn test_comment_block() {
        assert_eq!(
            mapok(comment_block(pinput("", "##hello Herman\n"))),
            Ok(("", vec!["hello Herman".into()]))
        );
        assert_eq!(
            mapok(comment_block(pinput("", "##hello Herman!"))),
            Ok(("", vec!["hello Herman!".into()]))
        );
        assert_eq!(
            mapok(comment_block(pinput("", "##hello\n##Herman\n"))),
            Ok(("", vec!["hello".into(), "Herman".into()]))
        );
        assert!(comment_block(pinput("", "hello Herman")).is_err());
    }

    #[test]
    fn test_metadata() {
        assert_eq!(
            mapok(metadata(pinput("", "@key=\"value\""))),
            Ok((
                "",
                PMetadata {
                    key: "key".into(),
                    value: PValue::String("value".to_string())
                }
            ))
        );
        assert_eq!(
            mapok(metadata(pinput("", "@key = \"value\""))),
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
    fn test_identifier() {
        assert_eq!(
            mapok(identifier(pinput("", "simple "))),
            Ok((" ", "simple".into()))
        );
        assert_eq!(
            mapok(identifier(pinput("", "simple?"))),
            Ok(("?", "simple".into()))
        );
        assert_eq!(
            mapok(identifier(pinput("", "simpl3 "))),
            Ok((" ", "simpl3".into()))
        );
        // TODO accept _
        //        assert_eq!(
        //            mapok(identifier(pinput("", "simple_word "))),
        //            Ok((" ", "simple_word".into()))
        //        );
        assert!(identifier(pinput("", "%imple ")).is_err());
        assert!(identifier(pinput("", "5imple ")).is_err());
    }

    #[test]
    fn test_parameter() {
        assert_eq!(
            mapok(parameter(pinput("", "hello "))),
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
            mapok(parameter(pinput("", "string:hello "))),
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
            mapok(parameter(pinput("", " string : hello "))),
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
            mapok(parameter(pinput("", " string : hello=\"default\""))),
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
    fn test_resource_def() {
        assert_eq!(
            mapok(resource_def(pinput("", "resource hello()"))),
            Ok((
                "",
                PResourceDef {
                    name: "hello".into(),
                    parameters: vec![]
                }
            ))
        );
        assert_eq!(
            mapok(resource_def(pinput("", "resource  hello2 ( )"))),
            Ok((
                "",
                PResourceDef {
                    name: "hello2".into(),
                    parameters: vec![]
                }
            ))
        );
        assert_eq!(
            mapok(resource_def(pinput("", "resource hello (string: p1, p2)"))),
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
            mapok(typed_value(pinput("", "\"\"\"This is a string\"\"\""))),
            Ok(("", PValue::String("This is a string".to_string())))
        );
        assert_eq!(
            mapok(typed_value(pinput("", "\"This is a string bis\""))),
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
    fn test_resource_ref() {
        assert_eq!(
            mapok(resource_ref(pinput("", "hello()"))),
            Ok((
                "",
                PResourceRef {
                    name: "hello".into(),
                    parameters: vec![]
                }
            ))
        );
        assert_eq!(
            mapok(resource_ref(pinput("", "hello3 "))),
            Ok((
                "",
                PResourceRef {
                    name: "hello3".into(),
                    parameters: vec![]
                }
            ))
        );
        assert_eq!(
            mapok(resource_ref(pinput("", "hello ( \"p1\", \"p2\" )"))),
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
            mapok(resource_ref(pinput("", "hello2 ( )"))),
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
    fn test_statement() {
        assert_eq!(
            mapok(statement(pinput("", "resource().state()"))),
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
            mapok(statement(pinput("", "resource().state( \"p1\", \"p2\")"))),
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
            mapok(statement(pinput("", "##hello Herman\n"))),
            Ok(("", PStatement::Comment(vec!["hello Herman".into()])))
        );
    }

    #[test]
    fn test_declaration() {
        assert_eq!(
            mapok(declaration(pinput("","ntp state configuration ()\n{\n  file(\"/tmp\").permissions(\"root\", \"root\", \"g+w\")\n}\n"))),
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
            mapok(state(pinput("", "resource state configuration() {}"))),
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
}
