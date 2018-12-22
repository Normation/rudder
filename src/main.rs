#[macro_use]
extern crate nom;

use std::fs;
use nom::IResult;
use std::fmt::Debug;
use nom::rest;
use nom::types::CompleteStr;


// STRUCTURES 
//
type PComment<'a> = Vec<&'a str>;

#[derive(Debug,PartialEq)]
pub struct PMetadata<'a> {
    pub key: &'a str,
    // TODO replace with a structure
    pub value: &'a str, 
}


// PARSERS adaptr for str instead of byte{]
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

// is_digit for char
// TODO there is probably and unicode function for that
#[inline]
pub fn is_digit_s(chr: char) -> bool {
    chr as u8 >= 0x30 && chr as u8 <= 0x39
}

// is_alphabetic for char
// TODO there is probably and unicode function for that
#[inline]
pub fn is_alphabetic_s(c:char) -> bool {
  (c as u8 >= 0x41 && c as u8 <= 0x5A) || (c as u8 >= 0x61 && c as u8 <= 0x7A)
}

// is_alphanumeric for char
// TODO there is probably and unicode function for that
#[inline]
pub fn is_alphanumeric_s(chr: char) -> bool {
  is_alphabetic_s(chr) || is_digit_s(chr)
}

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

// identifier
named!(identifier<&str,&str>,
  take_while1_s!(is_alphanumeric_s)
);

// comments
named!(comment_line<&str,&str>,
  preceded!(tag!("#"),
  // TODO use exact instead on complete ?
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
            key: identifier >>
            char!('=') >>
            value: delimited_string >>
            (PMetadata {key, value})
  ))
);

// XXX
named!(get_greeting<&str,&str>,
    tag_s!("hi")
);

named!(module<&str,Vec<&str>>,
    many0!(comment_line)
);

fn dump<T: Debug>(res: IResult<&str,T>) {
    match res {
      Ok((rest, value)) => {println!("Done {:?} << {:?}",rest,value)},
      Err(err) => {println!("Err {:?}",err)},
    }
}

fn main() {
    let filename = "test.ncf";
    let contents = fs::read_to_string(filename)
        .expect("Something went wrong reading the file");
    dump(comment_line(&contents));
}

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
}
