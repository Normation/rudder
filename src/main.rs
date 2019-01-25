#[macro_use]
mod error;
mod parser;
mod globalcontext;

use std::fs;
use enum_primitive::*;
use crate::error::*;

// MAIN
//
fn parser_err(context: &nom::Context<parser::PInput,u32>) -> Result<parser::PFile<'static>>{
    match context {
        nom::Context::Code(i,e) => {
            let (file,line,col) = parser::PToken::from(*i).position();
            match e {
                nom::ErrorKind::Custom(err) => Err(Error::Parsing(format!("Error: {} at {}:{}:{}",parser::PError::from_u32(*err).unwrap(),file,line,col),file,line,col)),
                e => Err(Error::Parsing(format!("Unprocessed parsing error '{:?}' at {}:{}:{}, please fill a BUG with context on when this happened",e, file,line,col), file,line,col)),
            }
        }
    }
}

fn parse<'a>(filename: &'a str, content: &'a str) -> Result<parser::PFile<'a>> {
    match parser::parse(parser::pinput(filename, content)) {
        Ok((_,pfile)) => Ok(pfile),
        Err(nom::Err::Failure(context)) => parser_err(&context),
        Err(nom::Err::Error(context)) => parser_err(&context),
        Err(nom::Err::Incomplete(_)) => panic!("Incomplete should never happen"),
    }
}

fn main() {
    let filename = "test.ncf";
    let content = fs::read_to_string(filename).expect(&format!("Something went wrong reading the file {}", filename));
//    match parse(filename, &content).and_then(analyser::analyse) {
//        Err(e) => println!("There was an error: {}", e),
//        Ok(_) => println!("Everything went OK"),
//    }

    // file = parameter
    // str = open read file
    // ast1 = parser::parse(str)
    // ast2 = analyser::analyse(ast1)
    // ast3 = optimizer::optimise(ast2)
    // generator::generate(ast3)
}
