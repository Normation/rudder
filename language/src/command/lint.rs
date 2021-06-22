// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use super::CommandResult;
use crate::command::compile::technique_to_ir;
use crate::{
    error::*, generator::new_generator, io::IOContext, ir::ir1::IR1, ir::ir2::IR2,
    language_lib::LanguageLib, parser::PAST,
};
use colored::Colorize;
use typed_arena::Arena;

// only parse and report errors, do not generate
pub fn lint(ctx: &IOContext, is_technique: bool) -> Result<Vec<CommandResult>> {
    let sources = Arena::new();
    let ir = technique_to_ir(ctx, &sources)?;
    Ok(vec![])
}
