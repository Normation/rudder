#[macro_use]
mod error;
mod globalcontext;
mod parser;

use std::fs;
use crate::globalcontext::generators::*;
// MAIN

// TODO next step:
// - Alt for semi analyzed data
// - outcome
// - cfengine cases
// - strings

fn main() {
    let mut gc = globalcontext::GlobalContext::new();
    let filename = "test.ncf";
    let content = fs::read_to_string(filename).expect(&format!(
        "Something went wrong reading the file {}",
        filename
    ));
    let file = match parser::parse_file(filename, &content) {
        Err(e) => panic!("There was an error during parsing:\n{}", e),
        Ok(o) => o,
    };
    match gc.add_pfile(filename, file) {
        Err(e) => panic!("There was an error during code insertion:\n{}", e),
        Ok(()) => {}
    };
    match gc.analyze() {
        Err(e) => panic!("There was an error during code analyse:\n{}", e),
        Ok(()) => {}
    };
    // optimize ?

    let mut cfe = cfengine::CFEngine::new();
    match cfe.generate_all(&gc) {
        Err(e) => panic!("There was an error during code generation:\n{}", e),
        Ok(()) => {}
    };
}
