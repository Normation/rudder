// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use crate::dsl::script::{Interpreter, InterpreterMode};
use crate::report::diff;
use crate::{AugeasParameters, RUDDER_LENS_LIB};
use raugeas::{Flags, SaveMode};
use rudder_module_type::{
    rudder_debug, rudder_error, rudder_info, CheckApplyResult, Outcome, PolicyMode,
};
use std::borrow::Cow;
use std::env;
use std::fs::read_to_string;

/// Augeas module implementation.
///
/// NOTE: We only support UTF-8 paths and values. This is constrained by
/// the usage of JSON in the API.
///
/// We store the Augeas instance across runs.
///
/// We never load the tree. Reading the lenses takes time, but is quite convenient, so we do it
/// once.
///
/// Below are some metrics for the different ways to run Augeas, based on `augtool` options:
///
/// * `-L` is for skipping loading the tree.
/// * `-A` is for skipping autoloading lenses (and hence the tree)
///
/// | Command | Mean \[ms\] | Min \[ms\] | Max \[ms\] | Relative |
/// |:---|---:|---:|---:|---:|
/// | `augtool -LA get /augeas/version` | 2.6 ± 0.5 | 1.6 | 4.6 | 1.00 |
/// | `augtool -L get /augeas/version` | 209.5 ± 5.2 | 200.2 | 221.5 | 80.72 ± 15.16 |
/// | `augtool get /augeas/version` | 663.0 ± 37.2 | 632.0 | 755.7 | 255.46 ± 49.69 |
///
/// Using:
///
/// ```shell
/// hyperfine -N  --export-markdown augtool.md
///     "augtool -LA get /augeas/version"
///     "augtool -L get /augeas/version"
///     "augtool get /augeas/version"
/// ```
pub struct Augeas {
    aug: raugeas::Augeas,
}

impl Augeas {
    pub fn new() -> anyhow::Result<Self> {
        // Cleanup the environment first to avoid any interference.
        //
        // SAFETY: Safe as the module is single threaded.
        unsafe {
            env::remove_var("AUGEAS_ROOT");
            env::remove_var("AUGEAS_LENS_LIB");
            env::remove_var("AUGEAS_DEBUG");
        }

        let aug = Self::new_aug(
            None, // Root is global in an agent context.
            false,
        )?;

        Ok(Augeas { aug })
    }

    pub fn new_aug(root: Option<&str>, type_check_lenses: bool) -> anyhow::Result<raugeas::Augeas> {
        let mut flags = Flags::NONE;

        // We never want to load the whole tree, but we load the lenses once.
        rudder_debug!("Autoloading lenses");
        flags.insert(Flags::NO_LOAD);

        if type_check_lenses {
            rudder_debug!("Type checking lenses");
            flags.insert(Flags::TYPE_CHECK);
        }

        let aug = raugeas::Augeas::init(root, RUDDER_LENS_LIB, flags)?;

        // Show version for debugging purposes.
        let version = aug.version()?;
        rudder_debug!("augeas version: {}", version);

        Ok(aug)
    }

    pub(crate) fn handle_check_apply(
        &mut self,
        p: AugeasParameters,
        policy_mode: PolicyMode,
    ) -> CheckApplyResult {
        let aug = &mut self.aug;

        // Set context if needed.
        let context: Option<Cow<str>> = if let Some(c) = p.context.as_deref() {
            Some(c.into())
        } else {
            p.path.as_deref().map(|p| format!("files/{p}").into())
        };
        if let Some(c) = &context {
            rudder_debug!("Setting context to: {c}");
            aug.set("/augeas/context", c.as_ref())?;
        }

        match (p.lens.as_deref(), p.path.as_deref()) {
            (Some(l), Some(p)) => {
                rudder_debug!("Using lens: {l}");
                aug.transform(l, p, false)?;
                aug.load()?;
            }
            (None, Some(p)) => {
                rudder_debug!("Detecting lens for path: {p}");
                aug.load_file(p)?;
            }
            _ => (),
        }

        // Do the changes before the checks.

        let mut interpreter = Interpreter::new(aug);

        // FIXME: handle check only and check script_if
        let do_script = match interpreter.run(InterpreterMode::ReadTree, &p.if_script) {
            Ok(_) => true,
            Err(e) => {
                rudder_error!("Error in if_script: {e}");
                false
            }
        };
        if do_script {
            rudder_debug!("Running script: {:?}", p.script);
            interpreter.run(InterpreterMode::WriteTree, &p.script)?;
        }

        // Avoid writing if we are in audit mode.
        match policy_mode {
            PolicyMode::Enforce => {
                aug.set_save_mode(SaveMode::Backup)?;
            }
            PolicyMode::Audit => {
                aug.set_save_mode(SaveMode::Noop)?;
            }
        }

        if let Some(path) = p.path.as_deref() {
            let src = read_to_string(path)?;
            let preview = aug.preview(path)?;

            let diff = diff(&src, &preview.unwrap());

            if p.show_diff {
                rudder_info!("Diff: {diff:?}");
            }
        }

        aug.save()?;

        // FIXME reload in case the tree is not clean

        // Ensure `files` are clean before next call.
        aug.load()?;

        // Get information about changes
        // make backups
        Ok(Outcome::Repaired("5 success".to_string()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rudder_module_type::Outcome;
    use std::fs::read_to_string;
    use tempfile::tempdir;

    fn arguments(script: String, path: Option<String>, lens: Option<String>) -> AugeasParameters {
        AugeasParameters {
            script,
            path,
            lens,
            ..Default::default()
        }
    }

    #[test]
    fn it_writes_file_from_commands() {
        let mut augeas = Augeas::new().unwrap();
        let d = tempdir().unwrap().into_path();
        let f = d.join("test");
        let lens = "Simplelines";

        let r = augeas
            .handle_check_apply(
                arguments(
                    format!("set /files{}/0 \"hello world\"", f.display()),
                    Some(f.display().to_string()),
                    Some(lens.to_string()),
                ),
                PolicyMode::Enforce,
            )
            .unwrap();
        assert_eq!(r, Outcome::repaired("5 success".to_string()));

        let content = read_to_string(&f).unwrap();
        assert_eq!(content.trim(), "hello world");
    }
}
