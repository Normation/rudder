#[cfg(unix)]
mod unix;
#[cfg(not(unix))]
mod windows;

mod common;
pub use common::{Hooks, RunHooks};
