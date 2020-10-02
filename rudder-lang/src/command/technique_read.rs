// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use super::{compile, CommandResult};
use crate::{error::Result, generator::Format, io::IOContext, technique::Technique};
use typed_arena::Arena;

/// Generate a JSON technique from a Rudderlang technique
pub fn technique_read(ctx: &IOContext) -> Result<Vec<CommandResult>> {
    let sources = Arena::new();
    let technique = Technique::from(&compile::technique_to_ir(ctx, &sources)?).to_json()?;

    Ok(vec![CommandResult::new(
        Format::JSON,
        ctx.output.clone(),
        Some(technique),
    )])
}
