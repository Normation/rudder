#[macro_use]
mod error;
mod ast;
mod parser;
mod technique;

use crate::ast::generators::*;
use crate::ast::{CodeIndex, AST};
use crate::parser::parse_file;
use crate::technique::translate_file;
use std::cell::UnsafeCell;
use std::fs;
use std::path::{Path,PathBuf};
use structopt::StructOpt;

// MAIN

// Questions :
// - compatibilité avec les techniques définissant des variables globales depuis une GM qui dépend d'une autre ?
// - usage du '!' -> "macros", enum expr, audit&test ?
// - sous typage explicite mais pas chiant
// - a qui s'applique vraiment les namespace ? variables, resources, enums, fonctions ? quels sont les default intelligents ?
// - a quoi ressemblent les iterators ?
// - arguments non ordonnés pour les resources et les states ?
// - usage des alias: pour les children, pour les (in)compatibilités, pour le générateur?

// TODO next step:


/// Rust langage compiler
#[derive(Debug, StructOpt)]
#[structopt(rename_all = "kebab-case")]
struct Opt {
    /// Output file or directory
    #[structopt(long,short)]
    output: PathBuf,
    /// Input file or directory
    #[structopt(long,short)]
    input: PathBuf,
    /// Set to use technique translation mode
    #[structopt(long,short)]
    translate: bool,
    /// Output format to use
    #[structopt(long,short="f")]
    output_format: Option<String>,
}

fn add_file<'a>(code_index: &mut CodeIndex<'a>, source_list: &'a SourceList, path: &Path, filename: &'a str) {
    let content = fs::read_to_string(path)
        .unwrap_or_else(|_| panic!("Something went wrong reading the file {}", filename));
    let content_str = source_list.append(content);
    let file = match parse_file(filename, content_str) {
        Err(e) => panic!("There was an error during parsing:\n{}", e),
        Ok(o) => o,
    };
    match code_index.add_parsed_file(filename, file) {
        Err(e) => panic!("There was an error during code insertion:\n{}", e),
        Ok(()) => {}
    };
}

/// Implementation of a linked list containing immutable data
/// but where we can append new data.
/// The goal is to be able to hold references to immutable data while
/// still appending new data at the end of the list.
pub struct SourceList(UnsafeCell<Option<(String, Box<SourceList>)>>);

impl SourceList {
    pub fn new() -> SourceList {
        SourceList(UnsafeCell::new(None))
    }
    pub fn append(&self, s: String) -> &str {
        let unsafe_ptr = self.0.get();
        let cell_ref = unsafe { &*unsafe_ptr };
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
    // easy optin parsing
    let opt = Opt::from_args();
    //println!("{:?}", opt);

    if opt.translate {
        match translate_file(&opt.input, &opt.output) {
            Err(e) => panic!("Error: {}", e),
            Ok(_) => println!("Done"),
        }
    } else {
        // TODO pass parameters
        compile(&opt.input)
    }
}

fn compile(source: &Path) {
    let mut code_index = CodeIndex::new();
    let sources = SourceList::new();

    // read and add files
    let stdlib = Path::new("stdlib.ncf");
    let filename = source.to_string_lossy();
    add_file(&mut code_index, &sources, stdlib, "stdlib.ncf");
    add_file(&mut code_index, &sources, source, &filename);

    // finish parsing into AST
    let ast = match AST::from_code_index(code_index) {
        Err(e) => panic!("There was an error during code structure check:\n{}", e),
        Ok(a) => a,
    };

    // check that everything is OK
    match ast.analyze() {
        Err(e) => panic!("There was an error during code analyse:\n{}", e),
        Ok(()) => {}
    };

    // generate final output
    let mut cfe = CFEngine::new();
    match cfe.generate(&ast, None) {
        Err(e) => panic!("There was an error during code generation:\n{}", e),
        Ok(()) => {}
    };
}

// Phase 2
// - function, measure(=fact), action
// - variable = anything
// - optimize before generation (remove unused code, simplify expressions ..)
// - inline native (cfengine, ...)
// - remediation resource (phase 3: add some reactive concept)
// - read templates and json a compile time
