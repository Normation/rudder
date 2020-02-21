// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use crate::{
    ast::AST,
    error::*,
    generators::*,
    parser::{Token, PAST},
    generate_oslib::generate_oslib,
};

use colored::Colorize;
use std::{
    cell::UnsafeCell,
    fs,
    path::Path
};

/// Read file, parse it and store it
fn add_file<'a>(
    past: &mut PAST<'a>,
    source_list: &'a SourceList,
    path: &'a Path,
    filename: &'a str,
) -> Result<()> {
    info!("|- {} {}", "Parsing".bright_green(), filename);
    match fs::read_to_string(path) {
        Ok(content) => {
            let content_str = source_list.append(content);
            past.add_file(filename, &content_str)
        }
        Err(e) => Err(err!(Token::new(filename, ""), "{}", e)),
    }
}

/// Implementation of a linked list containing immutable data
/// but where we can append new data.
/// The goal is to be able to hold references to immutable data while
/// still appending new data at the end of the list.
#[derive(Default)]
pub struct SourceList(UnsafeCell<Option<(String, Box<SourceList>)>>);

impl SourceList {
    pub fn new() -> SourceList {
        SourceList(UnsafeCell::new(None))
    }
    pub fn append(&self, s: String) -> &str {
        let unsafe_ptr = self.0.get();
        let cell_ref = unsafe { &*unsafe_ptr };
        if cell_ref.is_none() {
            unsafe {
                *unsafe_ptr = Some((s, Box::new(SourceList(UnsafeCell::new(None)))));
                &(&*unsafe_ptr).as_ref().unwrap().0
            }
        } else {
            cell_ref.as_ref().unwrap().1.append(s)
        }
    }
}

pub fn compile_file(source: &Path, dest: &Path, technique: bool) -> Result<()> {
    let sources = SourceList::new();
    
    let oslib = "libs/oslib.rl";
    generate_oslib("libs/osbuilder.ron", oslib)?;

    // read and add files
    let oses = Path::new(oslib);
    let corelib = Path::new("libs/corelib.rl");
    let cfenginecore = Path::new("libs/cfengine_core.rl");
    let stdlib = Path::new("libs/stdlib.rl");
    let input_filename = source.to_string_lossy();
    let output_filename = dest.to_string_lossy();

    info!(
        "{} of {} into {}",
        "Processing compilation".bright_green(),
        input_filename.bright_yellow(),
        output_filename.bright_yellow()
    );

    // data
    let mut past = PAST::new();
    add_file(&mut past, &sources, corelib, "corelib.rl")?;
    add_file(&mut past, &sources, cfenginecore, "cfengine_core.rl")?;
    add_file(&mut past, &sources, stdlib, "stdlib.rl")?;
    add_file(&mut past, &sources, oses, "oslib.rl")?;
    add_file(&mut past, &sources, source, &input_filename)?;

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
    cfe.generate(&ast, input_file, output_file, technique)
}
