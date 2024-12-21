// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use crate::AugeasParameters;
use raugeas::{CommandsNumber, Flags, SaveMode};
use rudder_module_type::{rudder_debug, CheckApplyResult, Outcome, PolicyMode};
use std::env;

/// Augeas module implementation.
///
/// NOTE: We only support UTF-8 paths and values. This is constrained by
/// the usage of JSON in the API.
///
/// We don't store the Augeas instance across runs.
///
/// We never load the tree. Reading the lenses takes time, but is quite convenient, so we offer
/// the options.
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
pub struct Augeas {}

// TODO: study allowing to store the instance and keeping it until we see
//       potential problems (e.g. commands).

impl Augeas {
    pub(crate) fn new() -> anyhow::Result<Self> {
        // Cleanup the environment first to avoid any interference.
        //
        // SAFETY: Safe as the module is single threaded.
        unsafe {
            env::remove_var("AUGEAS_ROOT");
            env::remove_var("AUGEAS_LENS_LIB");
            env::remove_var("AUGEAS_DEBUG");
        }

        Ok(Augeas {})
    }

    pub(crate) fn handle_check_apply(
        &self,
        p: AugeasParameters,
        policy_mode: PolicyMode,
    ) -> CheckApplyResult {
        let mut flags = Flags::NONE;

        if let Some(l) = p.lens.as_ref() {
            rudder_debug!("Using lens: {l}, skipping autoloading");
            flags.insert(Flags::NO_MODULE_AUTOLOAD);
        } else {
            // We never want to load the whole tree.
            rudder_debug!("Autoloading lenses");
            flags.insert(Flags::NO_LOAD);
        }

        if p.type_check_lenses {
            rudder_debug!("Type checking lenses");
            flags.insert(Flags::TYPE_CHECK);
        }

        let root: Option<&str> = p.root.as_deref();
        // FIXME root
        let mut aug = raugeas::Augeas::init("/", &p.load_paths(), flags)?;

        // Show version for debugging purposes.
        let version = aug.version()?;
        rudder_debug!("Augeas version: {}", version);

        //////////////////////////////////
        // Start with the special unsafe mode
        //////////////////////////////////

        if !p.commands.is_empty() {
            // Only allowed in "enforce" mode.
            // Makes little sense for audit mode, and risks making changes.
            // Already validated but make sure.
            assert_eq!(policy_mode, PolicyMode::Enforce);

            rudder_debug!("Running commands: {:?}", p.commands);
            let (num, out) = aug.srun(&p.commands.join("\n"))?;
            let summary = match num {
                CommandsNumber::Success(n) => format!("{n} success"),
                CommandsNumber::Quit => "has quit".to_string(),
            };
            rudder_debug!("{summary}, output: {out}");
            return Ok(Outcome::repaired(summary));
        }

        //////////////////////////////////
        // Now in "normal" mode:
        //
        // * We only work on one file.
        // * We handle backups, diffs, precise reporting.
        // * We guarantee that the policy mode is respected.
        //////////////////////////////////

        let lens_opt = p.lens_name();
        let _context = p.context();
        let path = p.path.as_deref().unwrap().to_string();

        if let Some(l) = lens_opt {
            // If we have a lens, we need to load it and load the file.
            aug.set(&format!("/augeas/load/${l}/lens"), l.as_ref())?;
            aug.set(&format!("/augeas/load/${l}/incl"), &path)?;
            aug.load()?;
        } else {
            // Else load it with the detected lens.
            // FIXME handle errors.
            aug.load_file(&path.to_string())?;
        }

        // Do the changes before the checks.

        let do_changes = if !p.change_if.is_empty() {
            rudder_debug!("Running change conditions: {:?}", p.change_if);
            // FIXME handle policy mode
            todo!()
        } else {
            true
        };

        if do_changes && !p.changes.is_empty() {
            rudder_debug!("Running changes: {:?}", p.changes);
            // FIXME handle policy mode
            todo!()
        }

        if !p.checks.is_empty() {
            rudder_debug!("Running checks: {:?}", p.checks);
            todo!()
        }

        match policy_mode {
            PolicyMode::Enforce => {
                aug.set_save_mode(SaveMode::Backup)?;
            }
            PolicyMode::Audit => {
                aug.set_save_mode(SaveMode::Noop)?;
            }
        }
        aug.save()?;

        // Get information about changes

        // make backups
        todo!()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs::read_to_string;
    use tempfile::tempdir;

    fn arguments(commands: Vec<String>) -> AugeasParameters {
        AugeasParameters {
            commands,
            ..Default::default()
        }
    }

    #[test]
    fn it_writes_file_from_commands() {
        let augeas = Augeas::new().unwrap();
        let d = tempdir().unwrap().into_path();
        let f = d.join("test");
        let lens = "Simplelines";

        let r = augeas
            .handle_check_apply(
                arguments(vec![
                    format!("set /augeas/load/${lens}/lens \"{lens}.lns\""),
                    format!("set /augeas/load/${lens}/incl \"{}\"", f.display()),
                    "load".to_string(),
                    format!("set /files{}/0 \"hello world\"", f.display()),
                    "save".to_string(),
                ]),
                PolicyMode::Enforce,
            )
            .unwrap();
        assert_eq!(r, Outcome::repaired("5 success".to_string()));

        let content = read_to_string(&f).unwrap();
        assert_eq!(content.trim(), "hello world");
    }
}
