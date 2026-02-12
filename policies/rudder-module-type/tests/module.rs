// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Normation SAS

// Minimal module type

use rudder_module_type::{
    CheckApplyResult, ModuleType0, ModuleTypeMetadata, Outcome, PolicyMode, parameters::Parameters,
    run_module,
};

struct Test {}

impl ModuleType0 for Test {
    fn metadata(&self) -> ModuleTypeMetadata {
        ModuleTypeMetadata::new("rudder-module-test", vec![])
    }

    fn check_apply(&mut self, _mode: PolicyMode, _attributes: &Parameters) -> CheckApplyResult {
        Ok(Outcome::Success(None))
    }
}

fn main() -> Result<(), anyhow::Error> {
    let test = Test {};
    run_module(test)
}
