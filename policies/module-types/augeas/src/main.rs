// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use std::{
    env, fs,
    fs::read_to_string,
    path::{Path, PathBuf},
};

use anyhow::{bail, Context, Result};
use raugeas::Flags;
use rudder_module_type::cfengine::called_from_agent;
use rudder_module_type::{
    backup::Backup, parameters::Parameters, rudder_debug, run_module, CheckApplyResult,
    ModuleType0, ModuleTypeMetadata, Outcome, PolicyMode, ValidateResult,
};
use serde::{Deserialize, Serialize};
use serde_json::Value;

pub const RUDDER_LENS_LIB: &str = "/var/rudder/lib/lenses";

// load_path are global to the augeas instance, so we can't set them per call.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub struct AugeasParameters {
    // Two modes : context + commands
    // or
    // lens + file

    /*
        changes
        context
        force ?? FIXME: check why
        incl
        lens
        load_path
        name
        onlyif
        provider
        root
        show_diff
        type_check

    */
    /// Output file path
    path: PathBuf,
    /// Source template path
    #[serde(skip_serializing_if = "Option::is_none")]
    template_path: Option<PathBuf>,
    /// Inlined source template
    #[serde(skip_serializing_if = "Option::is_none")]
    template_src: Option<String>,

    /// A lens to use
    #[serde(skip_serializing_if = "Option::is_none")]
    lens: Option<String>,
    #[serde(default)]
    type_check_lenses: bool,
}

struct Augeas {
    inner: raugeas::Augeas,
}

impl Augeas {
    fn new() -> Result<Self> {
        // Cleanup the environment first to avoid any interference.
        //
        // SAFETY: Safe as the module is single threaded.
        unsafe {
            env::remove_var("AUGEAS_ROOT");
            env::remove_var("AUGEAS_LENS_LIB");
            env::remove_var("AUGEAS_DEBUG");
        }

        // Don't load the tree but load the lenses
        // As we only load the module once, it will only be done once per run and
        // allows autoloading of lenses on paths.
        let flags = Flags::NO_LOAD;
        let inner = raugeas::Augeas::init(None, RUDDER_LENS_LIB, flags)?;

        let version = inner.version()?;
        rudder_debug!("Using augeas version: {}", version);

        Ok(Augeas { inner })
    }
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
        let parameters: AugeasParameters =
            serde_json::from_value(Value::Object(parameters.data.clone()))?;

        match (
            parameters.template_path.is_some(),
            parameters.template_src.is_some(),
        ) {
            (true, true) => bail!("Only one of 'template_path' and 'template_src' can be provided"),
            (false, false) => {
                bail!("Need one of 'template_path' and 'template_src'")
            }
            _ => (),
        }

        Ok(())
    }

    fn check_apply(&mut self, mode: PolicyMode, parameters: &Parameters) -> CheckApplyResult {
        assert!(self.validate(parameters).is_ok());
        let p: AugeasParameters = serde_json::from_value(Value::Object(parameters.data.clone()))?;

        Ok(Outcome::Success(None))
    }
}

fn main() -> Result<(), anyhow::Error> {
    let promise_type = Augeas::new()?;

    if called_from_agent() {
        run_module(promise_type)
    } else {
        unimplemented!("Only CFEngine mode is supported")
    }
}
