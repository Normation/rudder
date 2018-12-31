mod parser;

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
    let contents = fs::read_to_string(filename).expect("Something went wrong reading the file");
    match parser::header(parser::pinput(&contents)) {
        Err(err) => println!("Err {:?}", err),
        Ok((rest, value)) => {
            println!("Version OK {:?}", value);
            dump(parser::code(rest));
        }
    }
}
