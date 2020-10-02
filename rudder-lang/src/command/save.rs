// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use super::CommandResult;
use crate::{
    error::Result, generator::Format, io::IOContext, rudderlang_lib::RudderlangLib,
    technique::Technique,
};
use typed_arena::Arena;

/// Generates RudderLang from JSON technique (constructed around Rudder Technique exported JSON techniques)
pub fn save(ctx: &IOContext) -> Result<Vec<CommandResult>> {
    let sources: Arena<String> = Arena::new();
    let json_input = sources.alloc(ctx.input.clone());
    let json_content = sources.alloc(ctx.input_content.clone());

    let lib = RudderlangLib::new(&ctx.stdlib, &sources)?;
    let technique = Technique::from_json(json_input, json_content, false)?.to_rudderlang(&lib)?;

    Ok(vec![CommandResult::new(
        Format::RudderLang,
        match &ctx.output {
            Some(path) => path.to_str().map(|refstr| refstr.into()), // into a PathBuf
            None => None,
        },
        Some(technique),
    )])
}
