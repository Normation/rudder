// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use crate::report::diff;
use crate::{
    AugeasParameters, RUDDER_LENS_LIB,
    dsl::{
        error::format_report,
        interpreter::{
            CheckMode, Interpreter, InterpreterOut, InterpreterOutcome, InterpreterPerms,
        },
    },
};
use anyhow::bail;
use bytesize::ByteSize;
use raugeas::{Flags, SaveMode};
use rudder_module_type::{
    CheckApplyResult, Outcome, PolicyMode, backup::Backup, rudder_debug, rudder_error,
};
use std::{
    borrow::Cow,
    env, fs,
    os::unix::fs::MetadataExt,
    path::{Path, PathBuf},
};

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
        let mut flags = Flags::NONE;
        flags.insert(Flags::NO_LOAD);
        // Enable span tracking for better error messages.
        // As we never load the whole tree, the cost is negligible.
        flags.insert(Flags::ENABLE_SPAN);

        Self::new_aug(root, load_paths, flags)
    }

    pub fn new_aug<T: AsRef<Path>>(
        root: Option<&Path>,
        load_paths: &[T],
        flags: Flags,
    ) -> anyhow::Result<raugeas::Augeas> {
        // Consider Rudder lib as part of the standard lib.
        let rudder_lib = if flags.contains(Flags::NO_STD_INCLUDE) {
            None
        } else {
            Some(RUDDER_LENS_LIB.into())
        };

        // Load from the given paths plus the default one.
        let load_paths = load_paths
            .iter()
            .map(|p| p.as_ref().to_string_lossy())
            .chain(rudder_lib)
            .collect::<Vec<Cow<str>>>()
            .join(":");

        rudder_debug!("Loading lenses from: {load_paths}");
        let aug = raugeas::Augeas::init(root, load_paths, flags)?;

        // Show version for debugging purposes.
        let version = aug.version()?;
        rudder_debug!("augeas version: {version}");

        Ok(aug)
    }

    pub(crate) fn handle_check_apply(
        &mut self,
        p: AugeasParameters,
        policy_mode: PolicyMode,
        backup_dir: Option<&Path>,
    ) -> CheckApplyResult {
        let aug = &mut self.aug;

        let mut report = String::new();
        let mut is_err = false;

        let already_exists = p.path.exists();
        let path_str = p.path.display().to_string();

        let current_content = if already_exists {
            Some(fs::read_to_string(&p.path)?)
        } else {
            None
        };

        if already_exists {
            // Avoid memory exhaustion by not loading the file if it is too big.
            // NOTE: this is not a security feature, as we don't defend against TOCTOU attacks.
            let size = p.path.metadata()?.size();
            let b_size = ByteSize::b(size);
            if b_size > p.max_file_size {
                bail!(
                    "File is too big to be loaded: {path_str} ({b_size} > {})",
                    p.max_file_size
                );
            }
            rudder_debug!("Target {path_str} already exists");
        } else if policy_mode == PolicyMode::Audit {
            rudder_error!("Target {path_str} does not exist");
            // Short-circuit the check.
            bail!("Audit target file '{path_str}' does not exist");
        } else {
            rudder_debug!("Target {path_str} does not exist");
        }

        if let Some(l) = p.lens.as_deref() {
            rudder_debug!("Using lens: {l}");
            aug.transform(l, p.path.as_os_str(), false)?;
        }
        if already_exists {
            aug.load_file(p.path.as_os_str())?;
        }

        // We have loaded the target, let's check for parsing errors.
        if let Some(e) = aug.tree_error(format!("/augeas/{path_str}"))? {
            if let (Some(pos), Some(content)) = (&e.position, current_content.as_ref()) {
                let report = format_report(
                    &format!("Load error: {}", e.kind),
                    &e.message,
                    pos.position..(pos.position + 1),
                    &path_str,
                    content,
                    None,
                );
                bail!("{report}");
            } else {
                bail!("Error loading target file '{path_str}': {e}");
            }
        }

        let context: Cow<str> = if let Some(c) = p.context.as_deref() {
            c.into()
        } else {
            format!("files/{path_str}").into()
        };
        rudder_debug!("Setting context to: {context}");
        aug.set("/augeas/context", context.as_ref())?;

        let mut interpreter = Interpreter::new(aug, current_content.as_deref());

        // FIXME: handle check only and check script_if

        let no_if_script = p.if_script.trim().is_empty();

        let do_script = if no_if_script {
            // There is no condition => always run the script.
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
                    rudder_error!("Error in if_script: {e}");
                    false
                }
            }
        };

        if do_script {
            rudder_debug!("Running script: {:?}", p.script);
            let res = interpreter.run(
                InterpreterPerms::ReadWriteTree,
                CheckMode::StackErrors,
                &p.script,
            );

            match res {
                Ok(InterpreterOut {
                    outcome,
                    output,
                    quit,
                }) => {
                    if quit {
                        bail!("Script quit unexpectedly: {output}");
                    }
                    match outcome {
                        InterpreterOutcome::Ok => {}
                        InterpreterOutcome::CheckErrors(errors) => {
                            for e in errors {
                                report.push_str(format!("{:?}", e).as_str());
                            }
                            is_err = true;
                        }
                    }
                }
                Err(e) => {
                    report.push_str(format!("{:?}", e).as_str());
                    is_err = true;
                }
            }

            // We have run the script once.
            //
            // Now let's check for idempotency by running it again.

            // FIXME: do it on new files too.
            if already_exists {
                let content_after1 = interpreter.preview(&p.path)?.unwrap();

                interpreter.run(
                    InterpreterPerms::ReadWriteTree,
                    CheckMode::StackErrors,
                    &p.script,
                )?;

                // TODO check result?

                let content_after2 = interpreter.preview(&p.path)?.unwrap();

                if content_after1 != content_after2 {
                    let diff = diff(&content_after1, &content_after2);
                    bail!("Non-idempotent script: {}, stopping:\n{}", p.script, diff);
                }
            }

            // TODO check:
            // - is if_script now returns false
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

        // TODO: No preview() when creating the file.

        /*
        let src = read_to_string(&p.path).unwrap_or("".to_string());
        let preview = aug.preview(p.path).unwrap().unwrap_or("".to_string());

        let diff = diff(&src, &preview);

        if p.show_diff {
            rudder_info!("Diff: {diff:?}");
        }*/

        aug.save()?;

        // NOTE: Here we could reload the library in case of error somewhere to avoid
        // influencing the following calls. But for now no cases where it is needed have been
        // identified.

        // Get information about changes
        let modified = true;

        // Make a backup if needed.
        if let Some(b) = backup_dir {
            if let Some(c) = &current_content {
                if modified {
                    let backup_file = Backup::BeforeEdit.backup_file(p.path.as_path());
                    fs::write(b.join(backup_file), c)?;
                }
            }
        }

        if let Some(r) = p.report_file {
            fs::write(r, &report)?;
        }

        if is_err {
            // The full error is in the report.
            bail!("Script failed");
        }
        Ok(Outcome::Repaired(report))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    use rudder_module_type::Outcome;
    use std::{fs, fs::read_to_string, path::PathBuf};
    use tempfile::tempdir;

    fn arguments(
        script: String,
        path: PathBuf,
        lens: Option<String>,
        size_mb: u64,
    ) -> AugeasParameters {
        AugeasParameters {
            script,
            path,
            lens,
            show_file_content: true,
            context: Some("".to_string()),
            max_file_size: ByteSize::mb(size_mb),
            ..Default::default()
        }
    }

    #[test]
    fn it_writes_file_from_commands_in_new_file() {
        let mut augeas = Augeas::new_module(None, vec![]).unwrap();
        let d = tempdir().unwrap().keep();
        let f = d.join("test");
        let lens = "Simplelines";

        let r = augeas
            .handle_check_apply(
                arguments(
                    format!("set /files{}/0 \"hello world\"", f.display()),
                    f.clone(),
                    Some(lens.to_string()),
                    10,
                ),
                PolicyMode::Enforce,
                None,
            )
            .unwrap();
        assert_eq!(r, Outcome::repaired("".to_string()));
        let content = read_to_string(&f).unwrap();
        assert_eq!(content.trim(), "hello world");
        fs::remove_dir_all(d).unwrap();
    }

    #[ignore]
    #[test]
    fn it_writes_file_from_commands_in_existing_file() {
        let mut augeas = Augeas::new_module(None, vec![]).unwrap();
        let d = tempdir().unwrap().keep();
        let f = d.join("test");
        let lens = "Simplelines";

        fs::write(&f, "hello").unwrap();

        let r = augeas
            .handle_check_apply(
                arguments(
                    dbg!(format!("set /files{}/1 \"world\"", f.display())),
                    f.clone(),
                    Some(lens.to_string()),
                    10,
                ),
                PolicyMode::Enforce,
                None,
            )
            .unwrap();
        assert_eq!(r, Outcome::repaired("".to_string()));
        let content = read_to_string(&f).unwrap();
        assert_eq!(content.trim(), "world");
        fs::remove_dir_all(d).unwrap();
    }

    #[test]
    fn it_stops_on_files_too_big() {
        let mut augeas = Augeas::new_module(None, vec![]).unwrap();
        let d = tempdir().unwrap().keep();
        let f = d.join("test");
        let lens = "Simplelines";

        fs::write(&f, "hello").unwrap();

        let r = augeas
            .handle_check_apply(
                arguments(
                    dbg!(format!("set /files{}/1 \"world\"", f.display())),
                    f.clone(),
                    Some(lens.to_string()),
                    0,
                ),
                PolicyMode::Enforce,
                None,
            )
            .err()
            .unwrap();
        assert!(r.to_string().starts_with("File is too big to be loaded"));
        assert!(r.to_string().ends_with(" (5 B > 0 B)"));
        fs::remove_dir_all(d).unwrap();
    }
}
