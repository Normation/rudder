#[macro_use]
mod error;
mod ast;
mod parser;

use std::fs;
use crate::parser::parse_file;
use crate::ast::AST;
use crate::ast::generators::*;
// MAIN

// TODO next step:
// - Alt + finalize
// - outcome
// - cfengine cases
// - strings

fn main() {
    let mut ast = AST::new();
    let filename = "test.ncf";
    let content = fs::read_to_string(filename).expect(&format!(
        "Something went wrong reading the file {}",
        filename
    ));
    let file = match parse_file(filename, &content) {
        Err(e) => panic!("There was an error during parsing:\n{}", e),
        Ok(o) => o,
    };
    match ast.add_parsed_file(filename, file) {
        Err(e) => panic!("There was an error during code insertion:\n{}", e),
        Ok(()) => {}
    };
    ast = match ast.finalize() {
        Err(e) => panic!("There was an error during code structure check:\n{}", e),
        Ok(a) => a,
    };
    match ast.analyze() {
        Err(e) => panic!("There was an error during code analyse:\n{}", e),
        Ok(()) => {}
    };

    // optimize ?

    let mut cfe = CFEngine::new();
    match cfe.generate_all(&ast) {
        Err(e) => panic!("There was an error during code generation:\n{}", e),
        Ok(()) => {}
    };
}
