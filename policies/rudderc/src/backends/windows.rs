// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

use anyhow::Result;

use super::Backend;
use crate::ir;

pub struct Windows;

impl Default for Windows {
    fn default() -> Self {
        Self::new()
    }
}

impl Windows {
    pub fn new() -> Self {
        Self
    }
}

impl Backend for Windows {
    fn generate(&self, _policy: ir::Technique) -> Result<String> {
        unimplemented!()
    }
}
