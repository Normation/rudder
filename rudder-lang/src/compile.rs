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

/// Parses the whole stdlib, ie everything that's in the library directory
pub fn parse_stdlib<'src>(past: &mut PAST<'src>, sources: &'src Arena<String>, libs_dir: &'src Path) -> Result<()> {
    let dir = match fs::read_dir(libs_dir) {
        Ok(d) => d,
        Err(_) => return Err(Error::User(format!("Unable to open library directory {:?}", libs_dir))),
    };
    for entry in dir {
        let path = match entry {
            Ok(e) => e.path(),
            Err(_) => return Err(Error::User(format!("Unable to read library directory content {:?}", libs_dir))),
        };
        if path.ends_with(".rl") {
            parse_file(past, sources, &path)?;
        }
    }
    Ok(())
}

/// Add a single file content to the sources and parse it
pub fn parse_file<'src>(past: &mut PAST<'src>, sources: &'src Arena<String>, path: &Path) -> Result<()> {
    let file_name = match path.file_name() {
        None => return Err(Error::User(format!("Invalid rl file name {:?}", path))),
        Some(p) => p
    };
    let file_name = String::from(file_name.to_string_lossy());
    info!("|- {} {}", "Parsing".bright_green(), file_name);
    match fs::read_to_string(path) {
        Ok(content) => {
            // store name and content in the arena to make it live long enough
            let file_name = sources.alloc(file_name.into());
            let content_str = sources.alloc(content);
            past.add_file(file_name, content_str)
        }
        Err(e) => return Err(Error::User(format!("Uname to read file file name {}", file_name))),
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
    info!(
        "{} of {} into {}",
        "Processing compilation".bright_green(),
        source.to_string_lossy().bright_yellow(),
        dest.to_string_lossy().bright_yellow()
    );

    parse_file(&mut past, &sources, &source)?;

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
