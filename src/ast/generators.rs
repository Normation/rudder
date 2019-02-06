mod cfengine;

pub use self::cfengine::CFEngine;
use super::AST;
use crate::error::*;

/// A generator is something that can generate final code for a given language from an AST
/// We want at least cfengine, dsc, mgmt
pub trait Generator {
    /// If file is None: Generate code for everything that has been parsed
    /// If file is some: Only generate code for a single source file (for incremental generation or for integration with existing files)
    fn generate(&mut self, gc: &AST, file: Option<&str>) -> Result<()>;
}
