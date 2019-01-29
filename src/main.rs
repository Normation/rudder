#[macro_use]
mod error;
mod parser;
mod globalcontext;

use std::fs;

// MAIN

// next step:
// - error list reporting
// - cfengine case

fn main() {
    let mut gc = globalcontext::GlobalContext::new();
    let filename = "test.ncf";
    let content = fs::read_to_string(filename).expect(&format!("Something went wrong reading the file {}", filename));
    let file = match parser::parse_file(filename, &content) {
        Err(e) => panic!("There was an error: {}", e),
        Ok(o) => o,
    };
    match gc.add_pfile(filename, file) {
        Err(e) => panic!("There was an error: {}", e),
        Ok(()) => {},
    };
    // analyse
    match gc.analyze() {
        Err(e) => panic!("There was an error: {}", e),
        Ok(()) => {},
    };
    // optimize
    // generate
    gc.generate_cfengine();
}
