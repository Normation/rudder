// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use crate::{
    error::*,
    generator::new_generator,
    io::IOContext,
    ir::ir1::IR1,
    ir::ir2::IR2,
    parser::{Token, PAST},
    rudderlang_lib::RudderlangLib,
    ActionResult,
};
use colored::Colorize;
use std::{
    fs,
    io::Read,
    path::{Path, PathBuf},
};
use typed_arena::Arena;

/// Add a single file content to the sources and parse it
pub fn parse_file<'src>(
    past: &mut PAST<'src>,
    sources: &'src Arena<String>,
    path: &Option<PathBuf>,
) -> Result<()> {
    match path {
        Some(file_path) => {
            let filename = sources.alloc(match file_path.file_name() {
                Some(file) => file.to_string_lossy().into(),
                None => return Err(Error::new(format!("{:?} should be a .rl file", path))),
            });
            info!(
                "|- {} {}",
                "Parsing".bright_green(),
                filename.bright_yellow()
            );
            match fs::read_to_string(file_path) {
                Ok(content) => {
                    let content_str = sources.alloc(content);
                    past.add_file(filename, content_str)
                }
                Err(e) => Err(err!(Token::new(filename, ""), "{}", e)),
            }
        }
        None => {
            sources.alloc("STDIN".to_owned());
            info!(
                "|- {} {}",
                "Parsing".bright_green(),
                "STDIN".bright_yellow()
            );
            let mut buffer = String::new();
            match std::io::stdin().read_to_string(&mut buffer) {
                Ok(content) => {
                    let content_str = sources.alloc(buffer);
                    past.add_file("STDIN", content_str)
                }
                Err(e) => Err(err!(Token::new("STDIN", ""), "{}", e)),
            }
        }
    }
}

pub fn technique_to_ir<'src>(
    ctx: &'src IOContext,
    sources: &'src Arena<String>,
) -> Result<IR2<'src>> {
    // add stdlib: resourcelib + corelib + oslib + cfengine_core
    let mut past = RudderlangLib::past(&ctx.stdlib, sources)?;

    // read and add files
    info!(
        "{} of {}",
        "Processing compilation".bright_green(),
        match &ctx.input {
            Some(input) => input.to_string_lossy(),
            None => "STDIN".into(),
        }
        .bright_yellow(),
    );

    parse_file(&mut past, &sources, &ctx.input)?;

    // finish parsing into IR
    info!("|- {}", "Generating intermediate code".bright_green());
    let ir1 = IR1::from_past(past)?;

    // check that everything is OK
    info!("|- {}", "Semantic verification".bright_green());
    let ir2 = IR2::from_ir1(ir1)?;

    Ok(ir2)
}

/// Compile a file from rudder-lang to cfengine / dsc / json
pub fn compile(ctx: &IOContext, technique: bool) -> Result<Vec<ActionResult>> {
    let sources = Arena::new();
    let ir = technique_to_ir(ctx, &sources)?;

    // generate final output
    info!("|- {}", "Generating output code".bright_green());
    let input = match &ctx.input {
        Some(i) => Some(i.as_path()),
        None => None,
    };
    let output = match &ctx.output {
        Some(o) => Some(o.as_path()),
        None => None,
    };

    let (input_file, output_file) = if technique {
        // TODO this should be a technique name not a file name
        (input, output)
    } else {
        (None, None)
    };
    let mut generator = new_generator(&ctx.format)?;
    generator.generate(&ir, input_file, output_file, technique)
}
