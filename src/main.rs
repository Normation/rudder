#[macro_use]
mod error;
mod ast;
mod parser;

use crate::ast::generators::*;
use crate::ast::{PreAST, AST};
use crate::parser::parse_file;
use std::fs;
use std::cell::UnsafeCell;

// MAIN

// Questions :
// - Séparateur de statement ';' '\n' rien ?
// - compatibilité avec les techniques définissant des variables globales
// - usage du '!' -> "macros", enum expr, audit&test ?
// - sous typage explicite mais pas chiant
// - quand/qui fait le check des variables json ?
// - a qui s'applique vraiment les namespace ? variables, resources, enums, fonctions ?
// - a quoi ressemblent les iterators ?
// - comment exprimer les incompatibilités d'état

// TODO next step:
// - more checks (variables like enums, multiple mapping)

fn add_file<'a>(pre_ast: &mut PreAST<'a>, source_list: &'a SourceList, filename: &'a str) {
    let content = fs::read_to_string(filename)
        .unwrap_or_else(|_| panic!("Something went wrong reading the file {}", filename));
    let content_str = source_list.append(content);
    let file = match parse_file(filename, content_str) {
        Err(e) => panic!("There was an error during parsing:\n{}", e),
        Ok(o) => o,
    };
    match pre_ast.add_parsed_file(filename, file) {
        Err(e) => panic!("There was an error during code insertion:\n{}", e),
        Ok(()) => {}
    };
}

/// Implementation of a linked list containing immutable data
/// but where we can append new data.
/// The goal is to be able to hold references to immutable data while
/// still appending new data at the end of the list.
pub struct SourceList (UnsafeCell<Option<(String,Box<SourceList>)>>);

impl SourceList {
    pub fn new() -> SourceList {
        SourceList(UnsafeCell::new(None))
    }
    pub fn append(&self, s: String) -> &str {
        let unsafe_ptr = self.0.get();
        let cell_ref = unsafe {&*unsafe_ptr};
        if cell_ref.is_none() {
            unsafe {
                *unsafe_ptr = Some((s, Box::new(SourceList(UnsafeCell::new(None)))));
                &(&*unsafe_ptr).as_ref().unwrap().0
            }
        } else {
            cell_ref.as_ref().unwrap().1.append(s)
        }
    }
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
    let sources = SourceList::new();

    // read and add files
    let stdlib = "stdlib.ncf";
    add_file(&mut pre_ast, &sources, stdlib);
    let filename = "test.ncf";
    add_file(&mut pre_ast, &sources, filename);

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
