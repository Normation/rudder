use crate::{
    compile::compile, error::Result, io::IOContext, rudderlang_lib::RudderlangLib,
    technique::Technique, ActionResult, Format,
};
use typed_arena::Arena;

/// Takes a JSON technique and generates its RudderLang, DSC< CFEngine equivalent wrapped into a single JSON object
pub fn technique_generate(ctx: &IOContext) -> Result<Vec<ActionResult>> {
    // push RL version to the final data object
    let sources: Arena<String> = Arena::new();
    let input = sources.alloc(format!("JSON based on {}", ctx.input));
    let content = sources.alloc(ctx.input_content.clone());
    let lib = RudderlangLib::new(&ctx.stdlib, &sources)?;
    let technique_fmt = Technique::from_json(input, &content, false)?.to_rudderlang(&lib)?;

    let mut wrapped_technique = vec![ActionResult::new(
        Format::RudderLang,
        match &ctx.output {
            Some(path) => path.to_str().map(|refstr| refstr.into()), // into a PathBuf
            None => None,
        },
        Some(technique_fmt.clone()),
    )];

    let updated_ctx = &ctx.with_input("HEAP").with_content(technique_fmt);

    // push cfengine version to the final data object
    wrapped_technique.extend(compile(&updated_ctx.with_format(Format::CFEngine), true)?);
    // push dsc version to the final data object
    wrapped_technique.extend(compile(&updated_ctx.with_format(Format::DSC), true)?);

    // TODO do not stop at first error: accumulate errors, create each ActionResult (empty) and return all errors
    // requires a working log / error system
    Ok(wrapped_technique)
}
