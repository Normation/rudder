// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use crate::output::{CommandBehavior, CommandCapture, ResultOutput};
use anyhow::{Result, bail};
use log::{debug, info};
use rudder_module_type::{rudder_debug, rudder_error, rudder_info};
use std::path::PathBuf;
use std::{
    fmt, fs,
    os::unix::prelude::{MetadataExt, PermissionsExt},
    path::Path,
    process::Command,
};

const HOOKS_DIR_COMPAT: &str = "/var/rudder/system-update/hooks.d/";
const HOOKS_DIR: &str = "/opt/rudder/var/system-update-hooks.d/";
const PRE_UPGRADE_HOOK: &str = "pre-upgrade";
const PRE_REBOOT_HOOK: &str = "pre-reboot";
const POST_HOOK_DIR: &str = "post-upgrade";

#[derive(Clone, Debug, Copy)]
pub enum Hooks {
    PreUpgrade,
    PreReboot,
    PostUpgrade,
}

impl fmt::Display for Hooks {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        match *self {
            Hooks::PreUpgrade => write!(f, "{PRE_UPGRADE_HOOK}"),
            Hooks::PreReboot => write!(f, "{PRE_REBOOT_HOOK}"),
            Hooks::PostUpgrade => write!(f, "{POST_HOOK_DIR}"),
        }
    }
}

/// We only support pretty modern Linux systems, should work fine
fn geteuid() -> u32 {
    fs::metadata("/proc/self")
        .map(|m| m.uid())
        .expect("Could not read /proc/self")
}

fn hook_is_runnable(path: &Path, euid: u32) -> Result<()> {
    let metadata = path.metadata()?;
    let user = metadata.uid();
    let perms = metadata.permissions();
    let mode = perms.mode();

    let is_executable = mode & 0o100 != 0;
    if !is_executable {
        bail!("Hook is not executable, skipping");
    }

    let is_writable_by_others = mode & 0o002 != 0;
    if is_writable_by_others {
        bail!("Hook is writable by everyone, skipping");
    }

    let is_owned_by_current_user = user == euid;
    if !is_owned_by_current_user {
        bail!("Hook is not owned by current user, skipping");
    }

    Ok(())
}

impl Hooks {
    /// Determine the hooks directory to use, considering backward compatibility.
    ///
    /// * If the new hooks directory does not exist, but the deprecated one does,
    ///   the deprecated one will be used with a warning.
    /// * In all other cases, the new hooks directory will be used.
    fn hooks_dir() -> PathBuf {
        // Only use one location for the whole event for consistency.
        let path_compat = Path::new(HOOKS_DIR_COMPAT);
        let path_new = Path::new(HOOKS_DIR);
        let actual_path = if !path_new.exists() && path_compat.exists() {
            info!("Using deprecated hook directory: {}", path_compat.display());
            path_compat
        } else {
            path_new
        };
        debug!("Using hook directory: {}", actual_path.display());
        actual_path.to_path_buf()
    }

    pub fn run(self) -> ResultOutput<()> {
        let path = Self::hooks_dir().join(self.to_string());
        Self::run_dir(path.as_path())
    }

    fn run_dir(path: &Path) -> ResultOutput<()> {
        let mut res = ResultOutput::new(Ok(()));
        let euid = geteuid();

        if !path.exists() {
            let s = format!(
                "The hook directory {} does not exist, skipping",
                path.display()
            );
            rudder_debug!("{}", s);
            res.stdout(s);
            return res;
        }
        if !path.is_dir() {
            let s = format!("{} exists but is not a directory, skipping", path.display());
            rudder_debug!("{}", s);
            res.stderr(s);
            return res;
        }

        let mut hooks: Vec<_> = fs::read_dir(path).unwrap().map(|r| r.unwrap()).collect();
        // Sort hooks by lexicographical order
        hooks.sort_by_key(|e| e.path());

        for hook in hooks {
            let p = hook.path();
            match hook_is_runnable(p.as_path(), euid) {
                Ok(()) => {
                    res.stdout(format!("Running hook '{}'", p.display()));
                    res.stderr(format!("Running hook '{}'", p.display()));
                    rudder_info!("Running hook '{}'", p.display());
                    let hook_res = ResultOutput::command(
                        Command::new(&p),
                        CommandBehavior::FailOnErrorCode,
                        CommandCapture::StdoutStderr,
                    );
                    res.stdout_lines(hook_res.stdout);
                    res.stderr_lines(hook_res.stderr);
                    if let Err(e) = hook_res.inner {
                        res.stderr(format!("Hook '{}' failed", p.display()));
                        rudder_error!("Hook '{}' failed", p.display());
                        res.inner = Err(e.context(format!("Hook '{}' failed", p.display())));
                        return res;
                    }
                }
                Err(e) => {
                    res.stderr(format!("Skipping hook '{}': {:?}", p.display(), e));
                    rudder_info!("Skipping hook '{}': {:?}", p.display(), e);
                    continue;
                }
            };
        }

        res
    }
}

#[cfg(test)]
mod tests {
    use std::{fs::File, os::unix::fs::PermissionsExt};

    use tempfile::tempdir;

    use super::*;

    #[test]
    fn test_hook_is_runnable() {
        let euid = geteuid();

        let dir = tempdir().unwrap();
        let file_path = dir.path().join("tempfile");
        let file = File::create(&file_path).unwrap();
        let metadata = file.metadata().unwrap();
        let mut permissions = metadata.permissions();

        permissions.set_mode(0o700);
        file.set_permissions(permissions.clone()).unwrap();
        assert!(hook_is_runnable(&file_path, euid).is_ok());

        permissions.set_mode(0o700);
        file.set_permissions(permissions.clone()).unwrap();
        assert!(hook_is_runnable(&file_path, euid + 1).is_err());

        permissions.set_mode(0o777);
        file.set_permissions(permissions.clone()).unwrap();
        assert!(hook_is_runnable(&file_path, euid).is_err());

        permissions.set_mode(0o666);
        file.set_permissions(permissions).unwrap();
        assert!(hook_is_runnable(&file_path, euid).is_err());
    }

    #[test]
    fn test_run_hooks_on_nonexisting_directory() {
        let dir = "/does/not/exist";
        let result = Hooks::run_dir(Path::new(dir));
        assert!(result.inner.is_ok());
    }

    #[test]
    fn test_run_hooks_on_empty_directory() {
        let dir = tempdir().unwrap();
        let dir_path = dir.path();
        let result = Hooks::run_dir(dir_path);
        assert!(result.inner.is_ok());
    }

    #[test]
    fn test_run_hooks_with_empty_script() {
        let dir = tempdir().unwrap();
        let dir_path = dir.path();
        let file_path = dir_path.join("tempfile");
        File::create(file_path).unwrap();

        let result = Hooks::run_dir(dir_path);
        assert!(result.inner.is_ok());
    }

    #[test]
    fn test_run_hooks_with_multiple_succeeding_scripts() {
        let dir = Path::new("tests/hooks/success");
        assert!(dir.exists());
        let result = Hooks::run_dir(dir);
        assert!(result.inner.is_ok());
        assert!(result.stdout.contains(&"success1\n".to_string()));
        assert!(result.stdout.contains(&"success2\n".to_string()));
        assert!(result.stdout.contains(&"success3\n".to_string()));
    }

    #[test]
    fn test_run_hooks_stops_on_failure() {
        let dir = Path::new("tests/hooks/failure");
        assert!(dir.exists());
        let result = Hooks::run_dir(dir);
        assert!(result.inner.is_err());
        assert!(result.stdout.contains(&"success1\n".to_string()));
        assert!(result.stderr.contains(&"failure2\n".to_string()));
        assert!(!result.stdout.contains(&"success3\n".to_string()));
    }
}
