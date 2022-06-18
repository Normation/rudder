// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

//! Target != Backend, we could have different target compiled by the same backend

use anyhow::Result;
use rudder_commons::Target;

pub use self::{unix::Unix, windows::Windows};
use crate::ir::Policy;

mod metadata;
pub mod unix;
pub mod windows;

/// A backend is something that can generate final code for a given language from an IR
pub trait Backend {
    // For now, we only generate one file content
    fn generate(&self, policy: Policy) -> Result<String>;
}

/// Select the right backend
pub fn backend(target: Target) -> Box<dyn Backend> {
    match target {
        Target::Unix => Box::new(Unix::new()),
        Target::Windows => Box::new(Windows::new()),
    }
}
