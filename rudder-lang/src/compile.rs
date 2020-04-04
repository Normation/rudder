// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use crate::{
    ast::AST,
    error::*,
    generators::*,
    parser::{Token, PAST},
};

use colored::Colorize;
use std::{fs, path::Path};
use typed_arena::Arena;
use walkdir::WalkDir;

/// Parses the whole stdlib
/// Parse all `.rl` files recursively to allow future layout changes.
pub fn parse_stdlib<'src>(
    past: &mut PAST<'src>,
    sources: &'src Arena<String>,
    libs_dir: &'src Path,
) -> Result<()> {
    fn is_rl_file(file: &Path) -> bool {
        file.extension().map(|e| e == "rl").unwrap_or(false)
    }

    let walker = WalkDir::new(libs_dir)
        .into_iter()
        .filter(|r| r.as_ref().map(|e| is_rl_file(e.path())).unwrap_or(true));
    for entry in walker {
        match entry {
            Ok(e) => {
                let path = e.path();
                parse_file(past, sources, path)?;
            }
            Err(e) => return Err(err!(Token::new(&libs_dir.to_string_lossy(), ""), "{}", e)),
        }
    }

    Ok(())
}

/// Add a single file content to the sources and parse it
pub fn parse_file<'src>(
    past: &mut PAST<'src>,
    sources: &'src Arena<String>,
    path: &Path,
) -> Result<()> {
    let filename = sources.alloc(match path.file_name() {
        Some(file) => file.to_string_lossy().to_string(),
        None => return Err(Error::User(format!("{:?} should be a .rl file", path))),
    });
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
    parse_stdlib(&mut past, &sources, libs_dir)?;

    // read and add files
    info!(
        "{} of {} into {}",
        "Processing compilation".bright_green(),
        source.to_string_lossy().bright_yellow(),
        dest.to_string_lossy().bright_yellow()
    );

    parse_file(&mut past, &sources, source)?;

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
