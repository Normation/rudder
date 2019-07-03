use nom::*;
use nom::bytes::complete::*;
use nom::character::complete::*;
use nom::combinator::*;
use nom_locate::LocatedSpanEx;

///// An identifier is a word that contains alphanumeric chars.
///// Be liberal here, they are checked again later
//pnamed!(pub pidentifier<Token>,
//    map!(take_while1!(|c: char| c.is_alphanumeric() || (c == '_')),
//        |x| x.into()
//    )
//);



pub fn pidentifier<'a,'b>(i: LocatedSpanEx<'a, &'b str, String>) -> IResult<LocatedSpanEx<'a, &'b str, String>, LocatedSpanEx<'a, &'b str, String>> {
//    tag("you")(i)
    map(
        take_while1(|c: char| c.is_alphanumeric() || (c == '_')),
        |x: LocatedSpanEx<'a, &'b str,String>| x
        )(i)
}

fn main() {
    let file="file".to_string();
    let code=LocatedSpanEx::new_info("you_pi # @name variable",&file);
    let (id,o) = pidentifier(code).unwrap();
    println!("Parsed: {}, {}",id.fragment,o.fragment);
}
