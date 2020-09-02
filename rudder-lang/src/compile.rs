// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use crate::{
    ir::ir1::IR1, ir::ir2::IR2,
    error::*,
    generator::new_generator,
    io::IOContext,
    parser::{Token, PAST},
    rudderlang_lib::RudderlangLib,
};
use colored::Colorize;
use std::{fs, path::Path};
use typed_arena::Arena;

/// Add a single file content to the sources and parse it
pub fn parse_file<'src>(
    past: &mut PAST<'src>,
    sources: &'src Arena<String>,
    path: &Path,
) -> Result<()> {
    let filename = sources.alloc(match path.file_name() {
        Some(file) => file.to_string_lossy().to_string(),
        None => return Err(Error::new(format!("{:?} should be a .rl file", path))),
    });
    info!(
        "|- {} {}",
        "Parsing".bright_green(),
        filename.bright_yellow()
    );
    match fs::read_to_string(path) {
        Ok(content) => {
            let content_str = sources.alloc(content);
            past.add_file(filename, content_str)
        }
        Err(e) => Err(err!(Token::new(filename, ""), "{}", e)),
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
        "{} of {} into {}",
        "Processing compilation".bright_green(),
        ctx.source.to_string_lossy().bright_yellow(),
        ctx.dest.to_string_lossy().bright_yellow()
    );

    parse_file(&mut past, &sources, &ctx.source)?;

    // finish parsing into IR
    info!("|- {}", "Generating intermediate code".bright_green());
    let ir1 = IR1::from_past(past)?;

    // check that everything is OK
    info!("|- {}", "Semantic verification".bright_green());
    let ir2 = IR2::from_ir1(ir1)?;

    Ok(ir2)
}

/// Compile a file from rudder-lang to cfengine
pub fn compile_file(ctx: &IOContext, technique: bool) -> Result<()> {
    let sources = Arena::new();
    let ir = technique_to_ir(ctx, &sources)?;

    // generate final output
    info!("|- {}", "Generating output code".bright_green());
    let (input_file, output_file) = if technique {
        // TODO this should be a technique name not a file name
        (Some(ctx.source.as_path()), Some(ctx.dest.as_path()))
    } else {
        (None, None)
    };
    let mut generator = new_generator(&ctx.format)?;
    generator.generate(&ir, input_file, output_file, technique)
}
