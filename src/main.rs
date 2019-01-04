mod parser;
//mod enums;
mod error;

use nom::IResult;
use std::fmt::Debug;
use std::fs;

// MAIN
//
fn dump<T: Debug>(res: IResult<parser::PInput, T>) {
    match res {
        Ok((rest, value)) => println!("Done {:?} << {:?}", rest, value),
        Err(err) => println!("Err {:?}", err),
    }
}

fn main() {
    let filename = "test.ncf";
    let content = fs::read_to_string(filename).expect("Something went wrong reading the file");
    dump(parser::parse(parser::pinput(filename, &content)));
}
