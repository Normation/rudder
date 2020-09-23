use crate::{
    error::Result, io::IOContext, logger::*, rudderlang_lib::RudderlangLib, technique::Technique,
    ActionResult, Format,
};
use colored::Colorize;
use std::{fs, io::Write, path::PathBuf};
use typed_arena::Arena;

/// Generates RudderLang from JSON technique (constructed around Rudder Technique exported JSON techniques)
pub fn migrate(ctx: &IOContext) -> Result<Vec<ActionResult>> {
    let sources: Arena<String> = Arena::new();
    let json_input = sources.alloc(ctx.input.clone());
    let json_content = sources.alloc(ctx.input_content.clone());

    let lib = RudderlangLib::new(&ctx.stdlib, &sources)?;
    let technique = Technique::from_json(json_input, json_content, false)?.to_rudderlang(&lib)?;

    Ok(vec![ActionResult::new(
        Format::RudderLang,
        match &ctx.output {
            Some(path) => path.to_str().map(|refstr| refstr.into()), // into a PathBuf
            None => None,
        },
        Some(technique),
    )])
}
