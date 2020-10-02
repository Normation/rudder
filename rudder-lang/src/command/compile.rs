// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use super::CommandResult;
use crate::{
    error::*, generator::new_generator, io::IOContext, ir::ir1::IR1, ir::ir2::IR2, parser::PAST,
    rudderlang_lib::RudderlangLib,
};
use colored::Colorize;
use typed_arena::Arena;

// only parses a single file content, used in rudderlib too
// rudderlib uses file / content directly, no IOContext to pass hance the Strings as parameters
pub fn parse_content<'src>(
    mut past: PAST<'src>,
    filename: &str,
    content: &str,
    sources: &'src Arena<String>,
) -> Result<PAST<'src>> {
    let filename = sources.alloc(filename.to_owned());
    let content = sources.alloc(content.to_owned());
    // parse file
    info!("{} {}", "Parsing".bright_green(), filename.bright_yellow());
    past.add_file(filename, content)?;
    Ok(past)
}

// only get the IR of all lib + file, used in read too
pub fn technique_to_ir<'src>(
    ctx: &'src IOContext,
    sources: &'src Arena<String>,
) -> Result<IR2<'src>> {
    let mut past = RudderlangLib::past(&ctx.stdlib, &sources)?;
    past = parse_content(past, &ctx.input, &ctx.input_content, sources)?;
    // finish parsing into IR
    info!("|- {}", "Generating intermediate code".bright_green());
    let ir1 = IR1::from_past(past)?;

    // check that everything is OK
    info!("|- {}", "Semantic verification".bright_green());
    let ir2 = IR2::from_ir1(ir1)?;

    Ok(ir2)
}

// compiles lib + file (from a string), used in generate too
pub fn compile(ctx: &IOContext, is_technique: bool) -> Result<Vec<CommandResult>> {
    let sources = Arena::new();
    let ir = technique_to_ir(ctx, &sources)?;
    // generate final output
    info!("|- {}", "Generating output code".bright_green());
    let output = match (is_technique, &ctx.output) {
        (true, Some(o)) => Some(o.as_path()),
        _ => None,
    };
    let mut generator = new_generator(&ctx.format)?;
    generator.generate(&ir, &ctx.input, output, is_technique)
}
