// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

#![allow(dead_code)]
pub mod augeas;
pub mod dsl;
mod parameters;
mod report;

use crate::parameters::{AugeasParameters, CfengineAugeasParameters};
use anyhow::bail;
use augeas::Augeas;
use rudder_module_type::{
    CheckApplyResult, ModuleType0, ModuleTypeMetadata, PolicyMode, ValidateResult,
    parameters::Parameters,
};
use serde_json::Value;
use std::env;

pub const RUDDER_LENS_LIB: &str = "/var/rudder/lib/lenses";

pub const CRATE_NAME: &str = env!("CARGO_PKG_NAME");
pub const CRATE_VERSION: &str = env!("CARGO_PKG_VERSION");

impl ModuleType0 for Augeas {
    fn metadata(&self) -> ModuleTypeMetadata {
        let meta = include_str!("../rudder_module_type.yml");
        let docs = include_str!("../README.md");
        ModuleTypeMetadata::from_metadata(meta)
            .expect("invalid metadata")
            .documentation(docs)
    }

    fn validate(&self, parameters: &Parameters) -> ValidateResult {
        // from_value does not allow zero-copy deserialization
        let p: CfengineAugeasParameters =
            serde_json::from_value(Value::Object(parameters.data.clone()))?;
        let p: AugeasParameters = p.into();
        p.validate()
    }

    fn check_apply(&mut self, mode: PolicyMode, parameters: &Parameters) -> CheckApplyResult {
        let p: CfengineAugeasParameters =
            serde_json::from_value(Value::Object(parameters.data.clone()))?;
        let p: AugeasParameters = p.into();
        p.validate()?;

        self.handle_check_apply(p, mode, Some(parameters.backup_dir.as_path()))
    }
}

pub fn entry() -> Result<(), anyhow::Error> {
    // SAFETY: The module is single-threaded.
    unsafe {
        env::set_var("LC_ALL", "C");
    }

    let promise_type = Augeas::new(None, vec![])?;
    if rudder_module_type::cfengine::called_from_agent() {
        rudder_module_type::run_module(promise_type)
    } else {
        bail!("This module is meant to be run from the agent, use `raugtool` instead");
    }
}
