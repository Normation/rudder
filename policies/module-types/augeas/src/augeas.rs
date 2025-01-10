// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use crate::dsl::script::{
    CheckMode, Interpreter, InterpreterOut, InterpreterOutcome, InterpreterPerms,
};
use crate::report::diff;
use crate::{AugeasParameters, RUDDER_LENS_LIB};
use anyhow::bail;
use raugeas::{Flags, SaveMode};
use rudder_module_type::{rudder_debug, rudder_error, CheckApplyResult, Outcome, PolicyMode};
use std::borrow::Cow;
use std::env;
use std::os::unix::fs::MetadataExt;
use std::path::{Path, PathBuf};

/// Augeas module implementation.
///
/// NOTE: We only support UTF-8 paths and values. This is constrained by
/// the usage of JSON in the API.
///
/// We store the Augeas instance across runs.
///
/// We never load the tree. Reading the lenses takes time, but is quite convenient, so we do it
/// once.
pub struct Augeas {
    aug: raugeas::Augeas,
    root: Option<PathBuf>,
    load_paths: Vec<PathBuf>,
}

#[derive(Debug, PartialEq, Copy, Clone)]
pub enum LoadMode {
    All,
    LensesOnly,
    Nothing,
}

impl Augeas {
    /// Create a new Augeas module.
    pub fn new_module(root: Option<PathBuf>, load_paths: Vec<PathBuf>) -> anyhow::Result<Self> {
        // Cleanup the environment first to avoid any interference.
        //
        // SAFETY: Safe as the module is single threaded.
        unsafe {
            env::remove_var("AUGEAS_ROOT");
            env::remove_var("AUGEAS_LENS_LIB");
            env::remove_var("AUGEAS_DEBUG");
        }

        let aug = Self::new_aug_for_module(root.as_deref(), &load_paths)?;

        Ok(Augeas {
            aug,
            root,
            load_paths,
        })
    }

    /// Close and recreate the Augeas instance.
    ///
    /// Allows recovering from errors and starting fresh.
    fn reset_augeas(&mut self) -> anyhow::Result<()> {
        self.aug = Self::new_aug_for_module(self.root.as_deref(), &self.load_paths)?;
        Ok(())
    }

    fn new_aug_for_module<T: AsRef<Path>>(
        root: Option<&Path>,
        load_paths: &[T],
    ) -> anyhow::Result<raugeas::Augeas> {
        // Enable span tracking for better error messages.
        Self::new_aug(root, load_paths, false, true, LoadMode::LensesOnly)
    }

    pub fn new_aug<T: AsRef<Path>>(
        root: Option<&Path>,
        load_paths: &[T],
        type_check_lenses: bool,
        enable_span: bool,
        load_mode: LoadMode,
    ) -> anyhow::Result<raugeas::Augeas> {
        let mut flags = Flags::NONE;

        match load_mode {
            LoadMode::All => {
                rudder_debug!("Loading all files into the tree on startup");
            }
            LoadMode::LensesOnly => {
                rudder_debug!("Loading lenses on startup");
                flags.insert(Flags::NO_LOAD);
            }
            LoadMode::Nothing => {
                rudder_debug!("Not loading lenses on startup");
                flags.insert(Flags::NO_MODULE_AUTOLOAD);
            }
        }

        if enable_span {
            rudder_debug!("Enabling span tracking");
            flags.insert(Flags::ENABLE_SPAN);
        }

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

        let already_exists = p.path.exists();

        if already_exists {
            // Avoid memory exhaustion by not loading the file if it is too big.
            // NOTE: this is not a security feature, as we don't defend against TOCTOU attacks.
            let size = p.path.metadata()?.size();
            // 10MB is a reasonable limit for config file.
            if size > 10_000_000 {
                bail!(
                    "File too big to load: {} ({}B > 10MB)",
                    p.path.display(),
                    size
                );
            }
            rudder_debug!("Target {} already exists", p.path.display());
        } else if policy_mode == PolicyMode::Audit {
            rudder_error!("Target {} does not exist", p.path.display());
            // Short-circuit the check.
            bail!("Audit target file '{}' does not exist", p.path.display());
        } else {
            rudder_debug!("Target {} does not exist", p.path.display());
        }

        if let Some(l) = p.lens.as_deref() {
            rudder_debug!("Using lens: {l}");
            aug.transform(l, p.path.as_os_str(), false)?;
        }
        if already_exists {
            aug.load_file(p.path.as_os_str())?;
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

        let no_if_script = p.if_script.trim().is_empty();

        let do_script = if no_if_script {
            // No condition, always run the script.
            true
        } else {
            match interpreter.run(
                InterpreterPerms::ReadTree,
                CheckMode::FailEarly,
                &p.if_script,
            ) {
                Ok(InterpreterOut {
                    outcome,
                    output,
                    quit,
                }) => {
                    if quit {
                        bail!("if_script quit unexpectedly: {}", output);
                    }
                    match outcome {
                        InterpreterOutcome::Ok => true,
                        // Some checks failed, do not run the script.
                        InterpreterOutcome::CheckErrors(errors) => {
                            for e in errors {
                                rudder_error!("if_script check error: {:?}", e);
                            }
                            false
                        }
                    }
                }
                Err(e) => {
                    rudder_error!("Error in if_script: {}", e);
                    false
                }
            }
        };

        if do_script {
            rudder_debug!("Running script: {:?}", p.script);
            interpreter.run(
                InterpreterPerms::ReadWriteTree,
                CheckMode::StackErrors,
                &p.script,
            )?;

            if already_exists {
                // FIXME : bug in preview??
                dbg!("ONE");

                let content_after1 = interpreter.preview(&p.path)?.unwrap();

                dbg!("ONE");

                interpreter.run(
                    InterpreterPerms::ReadWriteTree,
                    CheckMode::StackErrors,
                    &p.script,
                )?;
                dbg!("ONE");

                let content_after2 = interpreter.preview(&p.path)?.unwrap();
                dbg!("ONE");

                if content_after1 != content_after2 {
                    let diff = diff(&content_after1, &content_after2);
                    bail!("Non-idempotent script: {}, stopping:\n{}", p.script, diff);
                }
            }

            // FIXME: comment détecter les non-convergences sur les nouveaux fichiers ??
            // peut-être pas possible

            // TODO check:
            // - if running twice changes the result (or triggers checks)
            // - is if_script now returns false
            //
            // This allows detecting non-idempotent configurations and avoid writing them.
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

        // TODO: Pas de preview() dispo en création de fichier

        /*
        let src = read_to_string(&p.path).unwrap_or("".to_string());
        let preview = aug.preview(p.path).unwrap().unwrap_or("".to_string());

        let diff = diff(&src, &preview);

        if p.show_diff {
            rudder_info!("Diff: {diff:?}");
        }*/

        aug.save()?;

        // FIXME reload in case the tree is not clean

        // Ensure `files` are clean before next call.
        //aug.load()?;

        // Get information about changes
        // make backups
        Ok(Outcome::Repaired("5 success".to_string()))
        // TODO text reporting with structured data
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    use rudder_module_type::Outcome;
    use std::fs;
    use std::fs::read_to_string;
    use std::path::PathBuf;
    use tempfile::tempdir;

    fn arguments(script: String, path: PathBuf, lens: Option<String>) -> AugeasParameters {
        AugeasParameters {
            script,
            path,
            lens,
            show_diff: true,
            context: Some("".to_string()),
            ..Default::default()
        }
    }

    #[test]
    fn it_writes_file_from_commands_in_new_file() {
        let mut augeas = Augeas::new_module(None, vec![]).unwrap();
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

    #[test]
    fn it_writes_file_from_commands_in_existing_file() {
        let mut augeas = Augeas::new_module(None, vec![]).unwrap();
        let d = tempdir().unwrap().into_path();
        let f = d.join("test");
        let lens = "Simplelines";

        fs::write(&f, "hello").unwrap();

        let r = augeas
            .handle_check_apply(
                arguments(
                    format!("set /files{}/1 \"world\"", f.display()),
                    f.clone(),
                    Some(lens.to_string()),
                ),
                PolicyMode::Enforce,
            )
            .unwrap();
        assert_eq!(r, Outcome::repaired("5 success".to_string()));
        let content = read_to_string(&f).unwrap();
        assert_eq!(content.trim(), "world");
    }
}
