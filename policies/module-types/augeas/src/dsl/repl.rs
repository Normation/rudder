// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use crate::dsl::script::{Interpreter, InterpreterPerms};
use anyhow::Result;
use rustyline::error::ReadlineError;

/// Start the `augtool`-like REPL.
pub fn start(interpreter: &mut Interpreter) -> Result<()> {
    let config = rustyline::Config::builder()
        .auto_add_history(true)
        .history_ignore_space(true)
        .build();

    let mut rl = rustyline::DefaultEditor::with_config(config)?;

    loop {
        let readline = rl.readline("raugtool> ");
        match readline {
            Ok(l) => match l.trim() {
                "" => continue,
                line => {
                    let res = interpreter.run(InterpreterPerms::ReadWriteSystem, line);
                    match res {
                        Ok(true) => break,
                        Ok(false) => (),
                        Err(e) => {
                            eprintln!("error: {:?}", e);
                        }
                    }
                }
            },
            // Ctrl-D
            Err(ReadlineError::Eof) => break,
            // Ctrl-C
            Err(ReadlineError::Interrupted) => break,
            Err(e) => return Err(e.into()),
        }
    }
    Ok(())
}
