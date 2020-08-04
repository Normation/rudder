// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use super::Generator;
use crate::{ast::AST, error::*, technique::Technique};
use std::convert::From;
use std::{fs::File, io::Write, path::Path};

pub struct JSON;

impl Generator for JSON {
    // TODO methods differ if this is a technique generation or not
    fn generate(
        &mut self,
        gc: &AST,
        _source_file: Option<&Path>,
        dest_file: Option<&Path>,
        _policy_metadata: bool,
    ) -> Result<()> {
        let content = Technique::from(gc).to_json()?;
        File::create(dest_file.expect("No destination to write on"))
            .expect("Could not create output file")
            .write_all(content.as_bytes())
            .expect("Could not write content into output file");
        Ok(())
    }
}
