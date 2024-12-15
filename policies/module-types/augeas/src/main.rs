// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

mod augeas;
mod check;

use anyhow::Result;
use augeas::Augeas;
use rudder_module_type::cfengine::called_from_agent;
use rudder_module_type::{
    parameters::Parameters, run_module, CheckApplyResult, ModuleType0, ModuleTypeMetadata,
    PolicyMode, ValidateResult,
};
use serde::{Deserialize, Serialize};
use serde_inline_default::serde_inline_default;
use serde_json::Value;

pub const RUDDER_LENS_LIB: &str = "/var/rudder/lib/lenses";

// load_path are global to the augeas instance, so we can't set them per call.
#[derive(Clone, Debug, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
#[serde_inline_default]
pub struct AugeasParameters {
    // Two modes : context + commands
    // or
    // lens + file

    // force ?? FIXME: check why, force l'écriture même sans changement détectés
    /// Raw commands to run.
    ///
    /// Should be used with care, as it can lead to override any configuration, including
    /// making changes in audit mode.
    ///
    /// When passed, all other parameters need to be empty.
    commands: Vec<String>,
    // changes
    // same syntax as only if?
    audits: Vec<String>,
    // only_if
    condition: Vec<String>,
    /// Prefix to add.
    context: Option<String>,
    /// Output file path
    #[serde(default)]
    // = incl
    path: Option<String>,
    /// Augeas root
    ///
    /// The root of the filesystem to use for augeas.
    ///
    /// WARNING: Should not be used in most cases.
    #[serde(default)]
    root: Option<String>,
    /// Show the diff.
    ///
    /// Enabled by default. Disable for files containing secrets.
    #[serde_inline_default(true)]
    show_diff: bool,

    /// Additional load paths for lenses.
    ///
    /// `/var/rudder/lib/lenses` is always added.
    load_lens_paths: Vec<String>,

    /// A lens to use.
    #[serde(skip_serializing_if = "Option::is_none")]
    lens: Option<String>,
    /// Force augeas to type-check the lenses.
    ///
    /// This is slow and should only be used for debugging.
    #[serde_inline_default(false)]
    type_check_lenses: bool,
}

impl ModuleType0 for Augeas {
    fn metadata(&self) -> ModuleTypeMetadata {
        let meta = include_str!("../rudder_module_type.yml");
        let docs = include_str!("../README.md");
        ModuleTypeMetadata::from_metadata(meta)
            .expect("invalid metadata")
            .documentation(docs)
    }

    fn validate(&self, parameters: &Parameters) -> ValidateResult {
        // Parse as parameters type
        let _parameters: AugeasParameters =
            serde_json::from_value(Value::Object(parameters.data.clone()))?;

        Ok(())
    }

    fn check_apply(&mut self, mode: PolicyMode, parameters: &Parameters) -> CheckApplyResult {
        assert!(self.validate(parameters).is_ok());
        let p: AugeasParameters = serde_json::from_value(Value::Object(parameters.data.clone()))?;

        self.handle_check_apply(p, mode)
    }
}

fn main() -> Result<(), anyhow::Error> {
    let promise_type = Augeas::new()?;

    if called_from_agent() {
        run_module(promise_type)
    } else {
        unimplemented!("Only agent mode is supported, use 'augtool' for manual testing")
    }
}
