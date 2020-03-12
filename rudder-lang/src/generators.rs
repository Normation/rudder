// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

mod cfengine;

pub use self::cfengine::CFEngine;
use crate::ast::AST;
use crate::error::*;
use std::path::Path;

/// A generator is something that can generate final code for a given language from an AST
/// We want at least cfengine, dsc, mgmt
pub trait Generator {
    /// If file is None: Generate code for everything that has been parsed
    /// If file is some: Only generate code for a single source file (for incremental generation or for integration with existing files)
    fn generate(
        &mut self,
        gc: &AST,
        input_file: Option<&Path>,
        output_file: Option<&Path>,
        translate_config: &Path,
        technique_metadata: bool,
    ) -> Result<()>;
}
