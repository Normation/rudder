// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

#![allow(clippy::large_enum_variant)]

//!  Principle:
//!  1-  rl -> PAST::add_file() -> PAST
//!         -> IR1::from_past -> IR1
//!         -> IR2::from_ir1 -> IR2
//!         -> ...
//!         -> generate() -> cfengine/json/...
//!
//!  2- json technique -> translate() -> rl
//!
//!  3- ncf library -> generate_lib() -> resourcelib.rl + translate-config

use colored::Colorize;
// imports log macros;
use log::*;
use std::process::exit;
use structopt::StructOpt;

use rudderc::{compile::compile_file, io, logger::Logger, opt::Opt, technique::generate, Action};

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

// Usage example (long / short version):
// cargo run -- --source tests/compile/s_basic.rl --dest tests/target/s_basic.rl --log-level debug --json-log-fmt
// cargo run -- -s tests/compile/s_basic.rl -d tests/target/s_basic.rl -l debug -j

// JSON log format note, read this when parsing json logs:
// { "input:: "str", "output": "str", "time": "timestamp unix epoch", "logs": [ ... ] }
// Default log format is `{ "status": "str", "message": "str" }`
// by exception another kind of log can be outputted: panic log or completion log
// completion (success or failure) log looks like this: "Compilation result": { "status": "str", "from": "str", "to": "str", "pwd": "str" }
// `panic!` log looks like this: { "status": "str", "message": "str" } (a lightweight version of a default log)

/// Rudder language compiler

// TODO use termination

fn main() {
    let command = Opt::from_args();
    let (logger, log_level, has_backtrace) = command.extract_logging_infos();
    let action = command.to_action();
    // Initialize logger
    logger.init(log_level, action, has_backtrace);

    let ctx = command.extract_parameters().unwrap_or_else(|e| {
        error!("{}", e);
        // required before returning in order to have proper logging
        logger.end(false, "input not set", "output not set");
        exit(1);
    });

    info!("I/O context: {}", ctx);

    // Actual action
    // read = rl -> json
    // migrate = cf -> rl
    // compile = rl -> cf / dsc
    // generate = json -> rl / cf / dsc
    // TODO make the right calls
    let result = match action {
        Action::Compile => compile_file(&ctx, true),
        // TODO Migrate: call cf_to_json perl script then call json->rl == Technique generate()
        Action::Migrate => unimplemented!(),
        // TODO: rl -> json + add a json wrapper : { data: {}, errors: {}}
        Action::ReadTechnique => unimplemented!(),
        // TODO Generate: call technique generate then compile into all formats + json wrapper: { rl: "", dsc: "", cf: "", errors:{} }
        Action::GenerateTechnique => unimplemented!(),
    };
    match &result {
        Err(e) => error!("{}", e),
        Ok(_) => info!(
            "{} {}",
            format!("{:?}", action).bright_green(),
            "OK".bright_cyan()
        ),
    };
    logger.end(result.is_ok(), ctx.input.display(), ctx.output.display());
    if result.is_err() {
        exit(1)
    }
}

// Phase 2
// - function, measure(=fact), action
// - variable = anything
// - optimize before generation (remove unused code, simplify expressions ..)
// - inline native (cfengine, ...)
// - remediation resource (phase 3: add some reactive concept)
// - read templates and json a compile time
