// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

mod augeas;
mod dsl;
mod parameters;

use crate::parameters::AugeasParameters;
use anyhow::{bail, Result};
use augeas::Augeas;
use rudder_module_type::cfengine::called_from_agent;
use rudder_module_type::{
    parameters::Parameters, run_module, CheckApplyResult, ModuleType0, ModuleTypeMetadata,
    PolicyMode, ValidateResult,
};
use serde_json::Value;

pub const RUDDER_LENS_LIB: &str = "/var/rudder/lib/lenses";

impl ModuleType0 for Augeas {
    fn metadata(&self) -> ModuleTypeMetadata {
        let meta = include_str!("../rudder_module_type.yml");
        let docs = include_str!("../README.md");
        ModuleTypeMetadata::from_metadata(meta)
            .expect("invalid metadata")
            .documentation(docs)
    }

    fn validate(&self, parameters: &Parameters) -> ValidateResult {
        let parameters: AugeasParameters =
            serde_json::from_value(Value::Object(parameters.data.clone()))?;
        parameters.validate(None)
    }

    fn check_apply(&mut self, mode: PolicyMode, parameters: &Parameters) -> CheckApplyResult {
        let p: AugeasParameters = serde_json::from_value(Value::Object(parameters.data.clone()))?;
        p.validate(Some(mode))?;

        self.handle_check_apply(p, mode)
    }
}

fn main() -> Result<(), anyhow::Error> {
    let promise_type = Augeas::new()?;

    if called_from_agent() {
        run_module(promise_type)
    } else {
        bail!("Only agent mode is supported, use 'augtool' for manual testing")
    }
}
