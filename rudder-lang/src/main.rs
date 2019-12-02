// Copyright 2019 Normation SAS
//
// This file is part of Rudder.
//
// Rudder is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// In accordance with the terms of section 7 (7. Additional Terms.) of
// the GNU General Public License version 3, the copyright holders add
// the following Additional permissions:
// Notwithstanding to the terms of section 5 (5. Conveying Modified Source
// Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
// Public License version 3, when you create a Related Module, this
// Related Module is not considered as a part of the work and may be
// distributed under the license agreement of your choice.
// A "Related Module" means a set of sources files including their
// documentation that, without modification of the Source Code, enables
// supplementary functions or services in addition to those offered by
// the Software.
//
// Rudder is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

#[macro_use]
mod error;
mod ast;
mod generators;
mod parser;
mod technique;

use crate::ast::AST;
use crate::error::*;
use crate::generators::*;
use crate::parser::Token;
use crate::parser::PAST;
use crate::technique::translate_file;
use std::cell::UnsafeCell;
use std::fs;
use std::path::{Path, PathBuf};
use structopt::StructOpt;

///!  Principle:
///!  1-  rl -> PAST::add_file() -> PAST
///!         -> AST::from_past -> AST
///!         -> generate() -> cfengine/json/...
///!
///!  2- json technique -> translate() -> rl
///!
///!  3- ncf library -> generate-lib() -> stdlib.rl + translate-config
///!

// MAIN

// Questions :
// - compatibilité avec les techniques définissant des variables globales depuis une GM qui dépend d'une autre ?
// - usage du '!' -> "macros", enum expr, audit&test ?
// - sous typage explicite mais pas chiant
// - a qui s'applique vraiment les namespace ? variables, resources, enums, fonctions ? quels sont les default intelligents ?
// - a quoi ressemblent les iterators ?
// - arguments non ordonnés pour les resources et les states ?
// - usage des alias: pour les children, pour les (in)compatibilités, pour le générateur?

// Next steps:
//
//

// TODO a state S on an object A depending on a condition on an object B is invalid if A is a descendant of B
// TODO except if S is the "absent" state

/// Rust langage compiler
#[derive(Debug, StructOpt)]
#[structopt(rename_all = "kebab-case")]
struct Opt {
    /// Output file or directory
    #[structopt(long, short)]
    output: PathBuf,
    /// Input file or directory
    #[structopt(long, short)]
    input: PathBuf,
    /// Set to use technique translation mode
    #[structopt(long)]
    translate: bool,
    /// Set to compile a single technique
    #[structopt(long)]
    technique: bool,
    /// Output format to use
    #[structopt(long, short = "f")]
    output_format: Option<String>,
}

/// Read file, parse it and store it
fn add_file<'a>(
    past: &mut PAST<'a>,
    source_list: &'a SourceList,
    path: &'a Path,
    filename: &'a str,
) -> Result<()> {
    let content = fs::read_to_string(path)
        .unwrap_or_else(|_| panic!("Something went wrong reading the file {}", filename));
    let content_str = source_list.append(content);
    past.add_file(filename, &content_str)
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

// TODO use termination
fn main() {
    // easy option parsing
    let opt = Opt::from_args();

    if opt.translate {
        match translate_file(&opt.input, &opt.output) {
            Err(e) => panic!("Error: {}", e),
            Ok(_) => println!("Done"),
        }
    } else {
        match compile(&opt.input, &opt.output, opt.technique) {
            Err(e) => panic!("Error: {}", e),
            Ok(_) => println!("Done"),
        }
    }
}

fn compile(source: &Path, dest: &Path, technique: bool) -> Result<()> {
    let sources = SourceList::new();

    // read and add files
    let corelib = Path::new("data/corelib.rl");
    let stdlib = Path::new("data/stdlib.rl");
    let filename = source.to_string_lossy();

    // data
    let mut past = PAST::new();
    add_file(&mut past, &sources, corelib, "corelib.rl")?;
    add_file(&mut past, &sources, stdlib, "stdlib.rl")?;
    add_file(&mut past, &sources, source, &filename)?;

    // finish parsing into AST
    let ast = AST::from_past(past)?;

    // check that everything is OK
    ast.analyze()?;

    // generate final output
    let mut cfe = CFEngine::new();
    let file = if technique {
        // TODO this should be a technique name not a file name
        Some(dest)
    } else {
        None
    };
    cfe.generate(&ast, file, technique)
}

// Phase 2
// - function, measure(=fact), action
// - variable = anything
// - optimize before generation (remove unused code, simplify expressions ..)
// - inline native (cfengine, ...)
// - remediation resource (phase 3: add some reactive concept)
// - read templates and json a compile time
