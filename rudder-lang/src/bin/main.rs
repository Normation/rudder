// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

#![allow(clippy::large_enum_variant)]

use rudderc::compile::compile_file;
use rudderc::translate::translate_file;
use structopt::StructOpt;
use colored::Colorize;
use std::path::PathBuf;

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

// TODO use termination
fn main() {
    // easy option parsing
    let opt = Opt::from_args();

    if opt.translate {
        match translate_file(&opt.input, &opt.output) {
            Err(e) => eprintln!("{}", e),
            Ok(_) => println!("{} {}", "File translation".bright_green(), "OK".bright_cyan()),
        }
    } else {
        match compile_file(&opt.input, &opt.output, opt.technique) {
            Err(e) => eprintln!("{}", e),
            Ok(_) => println!("{} {}", "Compilation".bright_green(), "OK".bright_cyan()),
        }
    }
}

// Phase 2
// - function, measure(=fact), action
// - variable = anything
// - optimize before generation (remove unused code, simplify expressions ..)
// - inline native (cfengine, ...)
// - remediation resource (phase 3: add some reactive concept)
// - read templates and json a compile time
