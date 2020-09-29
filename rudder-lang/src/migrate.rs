use crate::{error::Result, io::IOContext, ActionResult};

// TODO Migrate: call cf_to_json perl script then call json->rl == Technique generate()
/// Generates RudderLang from CFEngine
pub fn migrate(ctx: &IOContext) -> Result<Vec<ActionResult>> {
    // Ok(vec![ActionResult::default()])
    unimplemented!()
}
