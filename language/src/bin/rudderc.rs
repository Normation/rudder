// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

//!  Commands:
//!  1- rd -> PAST::add_file() -> PAST
//!         -> IR1::from_past -> IR1
//!         -> IR2::from_ir1 -> IR2
//!         -> ...
//!         -> compile() -> cfengine/dsc
//!
//!  2- json technique -> save() -> rd technique
//!
//!  3- rd technique -> technique read() -> json technique
//!
//!  4- json technique -> technique generate() -> JSON wrapper { dsc + rd + cf }

// Questions :
// - compatibilité avec les techniques définissant des variables globales depuis une GM qui dépend d'une autre ?
// - usage du '!' -> "macros", enum expr, audit&test ?
// - sous typage explicite mais pas chiant
// - a qui s'applique vraiment les namespace ? variables, resources, enums, fonctions ? quels sont les default intelligents ?
// - a quoi ressemblent les iterators ?
// - arguments non ordonnés pour les resources et les states ?
// - usage des alias: pour les children, pour les (in)compatibilités, pour le générateur?

// TODO a state S on an object A depending on a condition on an object B is invalid if A is a descendant of B
// TODO except if S is the "absent" state

// Usage example (long / short version):
// cargo run -- --source tests/compile/s_basic.rd --dest tests/target/s_basic.rd --log-level debug --json-log-fmt
// cargo run -- -s tests/compile/s_basic.rd -d tests/target/s_basic.rd -l debug -j

// JSON log format note, read this when parsing json logs:
// { "input:: "str", "output": "str", "time": "timestamp unix epoch", "logs": [ ... ] }
// Default log format is `{ "status": "str", "message": "str" }`
// by exception another kind of log can be outputted: panic log or completion log
// completion (success or failure) log looks like this: "Compilation result": { "status": "str", "from": "str", "to": "str", "pwd": "str" }
// `panic!` log looks like this: { "status": "str", "message": "str" } (a lightweight version of a default log)

// Phase 2
// - function, measure(=fact), command
// - variable = anything
// - optimize before generation (remove unused code, simplify expressions ..)
// - inline native (cfengine, ...)
// - remediation resource (phase 3: add some reactive concept)
// - read templates and json a compile time

#![allow(clippy::large_enum_variant)]

use rudderc::{
    command::{self, Command},
    error::Error,
    io::cli_parser::CLI,
};
use std::process::exit;
use structopt::StructOpt;

/// Rudder language compiler

// TODO use termination
fn main() {
    let cli = CLI::from_args();
    let (output, log_level, is_backtraced) = cli.extract_logging_infos();
    let command = cli.as_command();
    // Initialize logger and output
    output.init(command, log_level, is_backtraced);
    let ctx = cli.extract_parameters().unwrap_or_else(|e| {
        let mut cmdline = String::new();
        for arg in std::env::args_os() {
            cmdline.push_str(&format!("{:?} ", arg));
        }
        // required before returning in order to have proper logging
        output.print(
            command,
            None,
            Err(Error::new(format!(
                "Could not parse parameters: {}\nCommand line was : {}",
                e, cmdline
            ))),
        );
        exit(1);
    });

    let command_result = match command {
        // compile = rd -> cf / dsc
        Command::Compile => command::compile(&ctx, true),
        // lint = rd -> ()
        Command::Lint => command::lint(&ctx, true),
        // save = json -> rd
        Command::Save => command::save(&ctx),
        // read = rd -> json
        Command::ReadTechnique => command::technique_read(&ctx),
        // generate = json -> json { rd + cf + dsc }
        Command::GenerateTechnique => command::technique_generate(&ctx),
    };
    let is_command_success = command_result.is_ok();
    output.print(command, Some(ctx.input), command_result);
    if !is_command_success {
        let mut cmdline = String::new();
        for arg in std::env::args_os() {
            cmdline.push_str(&format!("{:?} ", arg));
        }
        output.print(
            command,
            None,
            Err(Error::new(format!("Command was {}", cmdline))),
        );
        exit(1)
    }
}
