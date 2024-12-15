// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use crate::{AugeasParameters, RUDDER_LENS_LIB};
use raugeas::{Flags, SaveMode};
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
        // Don't load the tree but load the lenses
        // TODO decide if we want to load the lenses or not
        let mut flags = Flags::NO_LOAD;
        if p.type_check_lenses {
            rudder_debug!("Type checking lenses");
            flags.insert(Flags::TYPE_CHECK);
        }
        let root = p.root.as_deref();

        let mut load_paths = p.load_lens_paths.clone();
        load_paths.push(RUDDER_LENS_LIB.to_string());

        let mut aug = raugeas::Augeas::init(root, &load_paths.join(":"), flags)?;

        let version = aug.version()?;
        rudder_debug!("Using augeas version: {}", version);

        let _a = aug.srun(&p.commands.join("\n"))?;
        match policy_mode {
            PolicyMode::Enforce => {
                aug.set_save_mode(SaveMode::Overwrite)?;
                aug.save()?;
            }
            PolicyMode::Audit => {
                aug.set_save_mode(SaveMode::Noop)?;
            }
        }
        aug.save()?;

        Ok(Outcome::repaired("".to_string()))
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
    fn it_does_nothing() {
        let augeas = Augeas::new().unwrap();
        let r = augeas
            .handle_check_apply(arguments(vec![]), PolicyMode::Enforce)
            .unwrap();
        assert_eq!(r, Outcome::repaired("".to_string()));
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
        assert_eq!(r, Outcome::repaired("".to_string()));

        let content = read_to_string(&f).unwrap();
        assert_eq!(content.trim(), "hello world");
    }
}
