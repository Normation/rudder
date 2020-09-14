use crate::{
    compile, error::Result, generator::Format, io::IOContext, technique::Technique, ActionResult,
};
use typed_arena::Arena;

/// Generate a JSON technique from a Rudderlang technique
pub fn technique_read(ctx: &IOContext) -> Result<Vec<ActionResult>> {
    let sources = Arena::new();
    let technique = Technique::from(&compile::technique_to_ir(ctx, &sources)?).to_json()?;

    Ok(vec![ActionResult::new(
        Format::JSON,
        ctx.output.clone(),
        Some(technique),
    )])
}
