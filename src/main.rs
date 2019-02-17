#[macro_use]
mod error;
mod ast;
mod parser;

use crate::ast::generators::*;
use crate::ast::{PreAST, AST};
use crate::parser::parse_file;
use std::fs;

// MAIN

// Questions :
// - Séparateur de statement ';' '\n' rien ?
// - compatibilité avec les techniques définissant des variables globales
// - includes: dans le main, dans les headers, partout ?
// - usage du '!' -> "macros", enum expr, audit&test ?
// - sous typage explicite mais pas chiant
// - quand/qui fait le check des variables json ?
// - a qui s'applique vraiment les namespace ? variables, resources, enums, fonctions ?

// TODO next step:
// - lifetime 'src
// - missing metadata/comments

fn read_file(filename: &str) -> String {
    fs::read_to_string(filename)
        .unwrap_or_else(|_| panic!("Something went wrong reading the file {}", filename))
}
fn add_file<'a>(pre_ast: &mut PreAST<'a>, filename: &'a str, content: &'a str) {
    let file = match parse_file(filename, &content) {
        Err(e) => panic!("There was an error during parsing:\n{}", e),
        Ok(o) => o,
    };
    match pre_ast.add_parsed_file(filename, file) {
        Err(e) => panic!("There was an error during code insertion:\n{}", e),
        Ok(()) => {}
    };
}

fn main() {
    // TODO argparse:
    // technique: read and compile a single file, automatically insert GMlib, output only one file
    // agent: read and compile a whole agent with a universe object, output a all files
    // --output-format=<cfengine|dsc|...>
    // --input-format=<agent|technique|...>
    // --input=file : main
    // --parse : output-format is json
    // --unparse : input-format is json, output format is a technique

    let mut pre_ast = PreAST::new();

    // read and add files
    let stdlib = "stdlib.ncf";
    let content = read_file(stdlib);
    add_file(&mut pre_ast, stdlib, &content);
    let filename = "test.ncf";
    let content = read_file(filename);
    add_file(&mut pre_ast, filename, &content);

    // finish parsing into AST
    let ast = match AST::from_pre_ast(pre_ast) {
        Err(e) => panic!("There was an error during code structure check:\n{}", e),
        Ok(a) => a,
    };
    // check that everything OK
    match ast.analyze() {
        Err(e) => panic!("There was an error during code analyse:\n{}", e),
        Ok(()) => {}
    };

    // optimize ?

    // generate final output
    let mut cfe = CFEngine::new();
    match cfe.generate(&ast, None) {
        Err(e) => panic!("There was an error during code generation:\n{}", e),
        Ok(()) => {}
    };
}
