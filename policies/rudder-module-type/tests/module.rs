// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Normation SAS

// Minimal module type

use rudder_module_type::{
    parameters::Parameters, run, CheckApplyResult, ModuleType0, ModuleTypeMetadata, Outcome,
    PolicyMode,
};

struct Test {}

impl ModuleType0 for Test {
    fn metadata(&self) -> ModuleTypeMetadata {
        let raw = include_str!("rudder_module_type.yml");
        ModuleTypeMetadata::from_metadata(raw).expect("invalid metadata")
    }

    fn check_apply(&mut self, _mode: PolicyMode, _attributes: &Parameters) -> CheckApplyResult {
        Ok(Outcome::Success(None))
    }
}

fn main() -> Result<(), anyhow::Error> {
    let test = Test {};
    run(test)
}
