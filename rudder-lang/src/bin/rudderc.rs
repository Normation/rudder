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

// imports log macros;
use log::*;
use std::process::exit;
use structopt::StructOpt;

use rudderc::{
    compile::compile,
    error::Error,
    migrate::migrate,
    opt::Opt,
    technique::{technique_generate, technique_read},
    Action,
};

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
// TODO log infos into ram to put into logs

fn main() {
    let command = Opt::from_args();
    let (output, log_level, has_backtrace) = command.extract_logging_infos();
    let action = command.as_action();
    // Initialize logger and output
    output.init(log_level, action, has_backtrace);
    let ctx = command.extract_parameters().unwrap_or_else(|e| {
        error!("{}", e);
        // required before returning in order to have proper logging
        output.print(
            action,
            "Input not set".to_owned(),
            Err(Error::new(
                "Could not determine proper I/O from given parameters".to_owned(),
            )),
        );
        exit(1);
    });
    info!("I/O context: {}", ctx);

    // Actual action
    // read = rl -> json
    // migrate = cf -> rl
    // compile = rl -> cf / dsc
    // generate = json -> json { rl + cf + dsc }
    // TODO collect logs in ram rather than directly printing it
    let action_result = match action {
        Action::Compile => compile(&ctx, true),
        Action::Migrate => migrate(&ctx),
        Action::ReadTechnique => technique_read(&ctx),
        Action::GenerateTechnique => technique_generate(&ctx),
    };
    let action_status = action_result.is_ok();
    output.print(action, ctx.input, action_result);
    if action_status {
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
