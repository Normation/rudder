// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use crate::dsl::changes::Changes;
use crate::{CRATE_NAME, CRATE_VERSION};
use anyhow::{Context, Result};
use raugeas::CommandsNumber;
use rustyline::error::ReadlineError;

/// Run a line of the DSL.
///
/// Returns `true` if the REPL should exit.
fn run(aug: &mut raugeas::Augeas, line: &str) -> Result<bool> {
    let try_changes = Changes::from_str(line);
    match try_changes {
        Ok(c) => {
            c.run(aug)?;
            Ok(false)
        }
        Err(_) => {
            let (r, out) = aug.srun(line).context("Failed to run augeas command")?;
            println!("{}", out.trim());
            match r {
                CommandsNumber::Quit => Ok(true),
                CommandsNumber::Success(_) => Ok(false),
            }
        }
    }
}

/// Start the `augtool`-like REPL.
pub fn start(aug: &mut raugeas::Augeas) -> Result<()> {
    let config = rustyline::Config::builder()
        .auto_add_history(true)
        .history_ignore_space(true)
        .build();

    let mut rl = rustyline::DefaultEditor::with_config(config)?;

    println!("Rudder Augeas. Type 'quit' to leave.");
    println!(
        "{} {} (augeas: {})",
        CRATE_NAME,
        CRATE_VERSION,
        aug.version()?
    );

    loop {
        let readline = rl.readline("raugtool> ");
        match readline {
            Ok(l) => match l.trim() {
                "" => continue,
                line => {
                    let res = run(aug, line);
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
