#[macro_use]
extern crate nom;

mod parser;

use std::fs;
use std::fmt::Debug;
use nom::IResult;

// MAIN
//
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
    dump(parser::comment_line(&contents));
}

