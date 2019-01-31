mod cfengine;

use super::AST;
use crate::error::*;
pub use self::cfengine::CFEngine;

pub trait Generator {
    fn generate_one(&mut self, gc: &AST, file: &str) -> Result<()>;
    fn generate_all(&mut self, gc: &AST) -> Result<()>;
}