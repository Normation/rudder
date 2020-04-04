// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use crate::{
    ast::AST,
    error::*,
    generators::*,
    parser::{Token, PAST},
};

use typed_arena::Arena;
use colored::Colorize;
use std::{fs, path::Path};

/// Parses the whole stdlib
pub fn parse_stdlib<'src>(past: &mut PAST<'src>, sources: &'src Arena<String>, libs_dir: &'src Path) -> Result<()> {
    let core_lib = libs_dir.join("corelib.rl");
    parse_file(past, sources, &core_lib, "corelib.rl")?;

    let oses = libs_dir.join("oslib.rl");
    parse_file(past, sources, &oses, "oslib.rl")?;

    let cfengine_core = libs_dir.join("cfengine_core.rl");
    parse_file(past, sources, &cfengine_core, "cfengine_core.rl")?;

    let stdlib = libs_dir.join("stdlib.rl");
    parse_file(past, sources, &stdlib, "stdlib.rl")?;

    Ok(())
}

/// Add a single file content to the sources and parse it
pub fn parse_file<'src>(past: &mut PAST<'src>, sources: &'src Arena<String>, path: &Path, filename: &'src str) -> Result<()> {
    info!("|- {} {}", "Parsing".bright_green(), filename);
    match fs::read_to_string(path) {
        Ok(content) => {
            let content_str = sources.alloc(content);
            past.add_file(filename, content_str)
        }
        Err(e) => Err(err!(Token::new(filename, ""), "{}", e)),
    }
}

/// Compile a file from rudder-lang to cfengine
pub fn compile_file(
    source: &Path,
    dest: &Path,
    technique: bool,
    libs_dir: &Path,
    translate_config: &Path,
) -> Result<()> {
    let sources = Arena::new();
    let mut past = PAST::new();

    // add stdlib
    parse_stdlib(&mut past, &sources, libs_dir);

    // read and add files
    let input_filename = source.to_string_lossy();
    let output_filename = dest.to_string_lossy();
    info!(
        "{} of {} into {}",
        "Processing compilation".bright_green(),
        input_filename.bright_yellow(),
        output_filename.bright_yellow()
    );

    parse_file(&mut past, &sources, &source, &input_filename)?;

    // finish parsing into AST
    info!("|- {}", "Generating intermediate code".bright_green());
    let ast = AST::from_past(past)?;

    // check that everything is OK
    info!("|- {}", "Semantic verification".bright_green());
    ast.analyze()?;

    // generate final output
    info!("|- {}", "Generating output code".bright_green());
    let mut cfe = CFEngine::new();
    let (input_file, output_file) = if technique {
        // TODO this should be a technique name not a file name
        (Some(source), Some(dest))
    } else {
        (None, None)
    };
    cfe.generate(&ast, input_file, output_file, translate_config, technique)
}
