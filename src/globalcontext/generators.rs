pub mod cfengine;

use super::GlobalContext;
use crate::error::*;

pub trait Generator {
    fn generate_one(&mut self, gc: &GlobalContext, file: &str) -> Result<()>;
    fn generate_all(&mut self, gc: &GlobalContext) -> Result<()>;
}