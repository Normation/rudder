// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use super::common::Hooks;
use crate::hooks::common::RunHooks;
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

/// We only support pretty modern Linux systems, should work fine
fn geteuid() -> u32 {
    fs::metadata("/proc/self")
        .map(|m| m.uid())
        .expect("Could not read /proc/self")
}
impl RunHooks for Hooks {
    fn hooks_dir(&self) -> PathBuf {
        let path_compat = Path::new("/var/rudder/system-update/hooks.d/");
        let path_new = Path::new("/opt/rudder/var/system-update-hooks.d/");
        let actual_path = if !path_new.exists() && path_compat.exists() {
            info!("Using deprecated hook directory: {}", path_compat.display());
            path_compat
        } else {
            path_new
        };
        debug!("Using hook directory: {}", actual_path.display());
        actual_path.to_path_buf()
    }

    fn is_runnable(&self, path: &Path) -> Result<()> {
        let euid = geteuid();
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
