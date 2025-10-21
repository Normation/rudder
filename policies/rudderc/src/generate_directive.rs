// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2025 Normation SAS

use crate::ir;
use anyhow::Result;
use indexmap::IndexMap;
use rudder_commons::PolicyMode;

pub trait GenerateDirective {
    fn generate_directive(
        &self,
        technique: ir::Technique,
        directive_id: &str,
        rule_id: &str,
        params: IndexMap<String, String>,
        policy_mode: PolicyMode,
    ) -> Result<String>;
}
