// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use crate::AugeasParameters;
use raugeas::{CommandsNumber, Flags, SaveMode};
use rudder_module_type::{rudder_debug, CheckApplyResult, Outcome, PolicyMode};
use std::env;

pub struct Augeas {}

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

        let mut aug = raugeas::Augeas::init(p.root.as_deref(), &p.load_paths(), flags)?;

        // Show version for debugging purposes.
        let version = aug.version()?;
        rudder_debug!("Augeas version: {}", version);

        //////////////////////////////////
        // Start with the special unsafe mode
        //////////////////////////////////

        // FIXME: find a way to use policy mode, at least by default.
        if !p.commands.is_empty() {
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
        // Now in "normal" mode
        //////////////////////////////////

        let path = p.path.clone().unwrap();

        if let Some(l) = p.lens() {
            // If we have a lens, we need to load it and load the file.
            aug.set(&format!("/augeas/load/${l}/lens"), &l.to_string())?;
            aug.set(&format!("/augeas/load/${l}/incl"), &path.to_string())?;
            aug.load()?;
        } else {
            // Else load it with the detected lens.
            // FIXME handle errors.
            aug.load_file(&path.to_string())?;
        }

        if !p.changes.is_empty() {
            rudder_debug!("Running changes: {:?}", p.changes);
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

        // Get info about changes

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
