// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

#![allow(clippy::large_enum_variant)]

use colored::Colorize;
// imports log macros;
use log::*;
use structopt::StructOpt;
use std::path::PathBuf;

use rudderc::{
    compile::compile_file,
    translate::translate_file,
    logger,
};


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


/// Usage example (long / short version):
/// cargo run -- --compile --input tests/compile/s_basic.rl --output tests/target/s_basic.rl --log-level debug --json-log-fmt
/// cargo run -- -c -i tests/compile/s_basic.rl -o tests/target/s_basic.rl -l debug -j

/// JSON log format note, read this when parsing json logs:
/// { "input:: "str", "output": "str", "time": "timestamp unix epoch", "logs": [ ... ] } 
/// Default log format is `{ "status": "str", "message": "str" }` 
/// by exception another kind of log can be outputted: panic log or completion log
/// completion (success or failure) log looks like this: "Compilation result": { "status": "str", "from": "str", "to": "str", "pwd": "str" }
/// `panic!` log looks like this: { "status": "str", "message": "str" } (a lightweight version of a default log)

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
    #[structopt(long, short)]
    translate: bool,
    /// Set to compile a single technique
    #[structopt(long, short)]
    compile: bool,
    /// Output format to use
    #[structopt(long, short = "f")]
    output_fmt: Option<String>,
    /// Set to change default env logger behavior (off, error, warn, info, debug, trace), default being warn
    #[structopt(long, short)]
    log_level: Option<LevelFilter>,
    /// Output format to use: standard terminal or json style
    #[structopt(long, short)]
    json_log_fmt: bool,
}

// TODO use termination


fn main() {
    // easy option parsing
    let opt = Opt::from_args();
    
    let exec_action = if opt.compile { "compile" } else { "translate" };
    
    logger::set(opt.log_level, opt.json_log_fmt, &opt.input, &opt.output, &exec_action);
    
    let result;
    if opt.translate {
        result = translate_file(&opt.input, &opt.output);
        match &result {
            Err(e) => error!("{}", e),
            Ok(_) => info!("{} {}", "File translation".bright_green(), "OK".bright_cyan()),
        }
    } else {
        result = compile_file(&opt.input, &opt.output, opt.compile);
        match &result {
            Err(e) => error!("{}", e),
            Ok(_) => info!("{} {}", "Compilation".bright_green(), "OK".bright_cyan()),
        }
    }

    logger::print_output_closure(
        opt.json_log_fmt,
        result.is_ok(),
        opt.input.to_str().unwrap_or("input file not found"),
        opt.output.to_str().unwrap_or("output file not found"),
        &exec_action
    );
}

// Phase 2
// - function, measure(=fact), action
// - variable = anything
// - optimize before generation (remove unused code, simplify expressions ..)
// - inline native (cfengine, ...)
// - remediation resource (phase 3: add some reactive concept)
// - read templates and json a compile time
