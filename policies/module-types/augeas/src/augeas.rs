// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use crate::dsl::script::{Interpreter, InterpreterPerms};
use crate::report::diff;
use crate::{AugeasParameters, RUDDER_LENS_LIB};
use anyhow::bail;
use raugeas::{Flags, SaveMode};
use rudder_module_type::{
    rudder_debug, rudder_error, rudder_info, CheckApplyResult, Outcome, PolicyMode,
};
use std::borrow::Cow;
use std::env;
use std::fs::read_to_string;
use std::path::Path;

/// Augeas module implementation.
///
/// NOTE: We only support UTF-8 paths and values. This is constrained by
/// the usage of JSON in the API.
///
/// We store the Augeas instance across runs.
///
/// We never load the tree. Reading the lenses takes time, but is quite convenient, so we do it
/// once.
//
pub struct Augeas {
    aug: raugeas::Augeas,
}

impl Augeas {
    /// Additional load paths for lenses.
    ///
    /// `/var/rudder/lib/lenses` is always added.
    pub fn new(root: Option<&Path>, load_paths: Vec<&Path>) -> anyhow::Result<Self> {
        // Cleanup the environment first to avoid any interference.
        //
        // SAFETY: Safe as the module is single threaded.
        unsafe {
            env::remove_var("AUGEAS_ROOT");
            env::remove_var("AUGEAS_LENS_LIB");
            env::remove_var("AUGEAS_DEBUG");
        }

        let aug = Self::new_aug(
            root, // Root is global in an agent context.
            load_paths, false,
        )?;

        Ok(Augeas { aug })
    }

    pub fn new_aug<T: AsRef<Path>>(
        root: Option<&Path>,
        load_paths: Vec<T>,
        type_check_lenses: bool,
    ) -> anyhow::Result<raugeas::Augeas> {
        let mut flags = Flags::NONE;

        // We never want to load the whole tree, but we load the lenses once.
        rudder_debug!("Autoloading lenses");
        flags.insert(Flags::NO_LOAD);

        if type_check_lenses {
            rudder_debug!("Type checking lenses");
            flags.insert(Flags::TYPE_CHECK);
        }

        // Load from the given paths plus the default one.
        let load_paths = std::iter::once(RUDDER_LENS_LIB.into())
            .chain(load_paths.iter().map(|p| p.as_ref().to_string_lossy()))
            .collect::<Vec<Cow<str>>>()
            .join(":");
        rudder_debug!("Loading lenses from: {}", load_paths);
        let aug = raugeas::Augeas::init(root, load_paths, flags)?;

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

        if p.path.exists() {
            rudder_debug!("Target {} already exists", p.path.display());
        } else {
            rudder_debug!("Target {} does not exist", p.path.display());
            if policy_mode == PolicyMode::Audit {
                rudder_error!("Target {} does not exist", p.path.display());
                // Short-circuit the check.
                bail!("Audit target file '{}' does not exist", p.path.display());
            } else {
                rudder_debug!("Target {} does not exist", p.path.display());
            }
        }

        match p.lens.as_deref() {
            Some(l) => {
                rudder_debug!("Using lens: {l}");
                aug.transform(l, p.path.as_os_str(), false)?;
                aug.load()?;
            }
            None => {
                rudder_debug!("Detecting lens for path: {}", p.path.display());
                aug.load_file(p.path.as_os_str())?;
            }
        }

        // We have loaded the target, let's check for parsing errors.
        if let Some(e) = aug.tree_error(format!("/augeas/{}", p.path.display()))? {
            bail!("Error loading target file '{}': {}", p.path.display(), e);
            // TODO: some errors might not be fatal?
        }

        let context: Cow<str> = if let Some(c) = p.context.as_deref() {
            c.into()
        } else {
            format!("files/{}", p.path.display()).into()
        };
        rudder_debug!("Setting context to: {context}");
        aug.set("/augeas/context", context.as_ref())?;

        let mut interpreter = Interpreter::new(aug);

        // FIXME: handle check only and check script_if
        let do_script = if p.if_script.trim().is_empty() {
            // No condition, always run the script.
            true
        } else {
            match interpreter.run(InterpreterPerms::ReadTree, &p.if_script) {
                Ok(_) => true,
                Err(e) => {
                    // FIXME: make a difference between audit errors and other errors!
                    rudder_error!("Error in if_script: {}", e);
                    false
                }
            }
        };
        if do_script {
            rudder_debug!("Running script: {:?}", p.script);
            interpreter.run(InterpreterPerms::ReadWriteTree, &p.script)?;
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

        let src = read_to_string(&p.path)?;
        let preview = aug.preview(p.path)?;

        let diff = diff(&src, &preview.unwrap());

        if p.show_diff {
            rudder_info!("Diff: {diff:?}");
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
    use std::path::PathBuf;
    use tempfile::tempdir;

    fn arguments(script: String, path: PathBuf, lens: Option<String>) -> AugeasParameters {
        AugeasParameters {
            script,
            path,
            lens,
            ..Default::default()
        }
    }

    #[test]
    fn it_writes_file_from_commands() {
        let mut augeas = Augeas::new(None, vec![]).unwrap();
        let d = tempdir().unwrap().into_path();
        let f = d.join("test");
        let lens = "Simplelines";

        let r = augeas
            .handle_check_apply(
                arguments(
                    format!("set /files{}/0 \"hello world\"", f.display()),
                    f.clone(),
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
