use crate::{
    compile::technique_to_ir, error::Result, generator::Format, io::IOContext,
    technique::Technique, ActionResult,
};
use typed_arena::Arena;

/// takes a RudderLang technique, and generate a JSON technique that wraps it up
// TODO report errors directly into the output (+data)
pub fn technique_read(ctx: &IOContext) -> Result<Vec<ActionResult>> {
    let sources = Arena::new();
    let technique = Technique::from(&technique_to_ir(ctx, &sources)?).to_json()?;

    // TODO Option -> output -> Some else None
    Ok(vec![ActionResult::new(
        Format::JSON,
        ctx.output.clone(),
        Some(technique),
    )])
}
