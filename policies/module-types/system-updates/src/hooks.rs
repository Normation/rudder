// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use crate::output::ResultOutput;
use anyhow::{bail, Result};
use rudder_module_type::{rudder_debug, rudder_info, rudder_warning};
use std::fmt::format;
use std::fs;
use std::fs::DirEntry;
use std::os::unix::prelude::{MetadataExt, PermissionsExt};
use std::path::Path;
use std::process::Command;

const HOOKS_DIR: &str = "/var/rudder/system-update/hooks.d/";
const PRE_HOOK: &str = "pre-upgrade";
const PRE_REBOOT_HOOK: &str = "pre-reboot";
const POST_HOOK_DIR: &str = "post-upgrade";

#[link(name = "c")]
extern "C" {
    fn geteuid() -> u32;
}

fn hook_is_runnable(path: &Path) -> Result<()> {
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

    unsafe {
        let is_owned_by_current_user = user == geteuid();
        if !is_owned_by_current_user {
            bail!("Hook is not owned by current user, skipping");
        }
    }

    Ok(())
}

fn run_hooks(path: &Path) -> ResultOutput<()> {
    let mut res = ResultOutput::new(Ok(()));

    if !path.exists() {
        let s = format!(
            "The hook directory {} does not exist, skipping",
            path.display()
        );
        rudder_debug!("{}", s);
        res.stderr(s);
        return res;
    }
    if !path.is_dir() {
        let s = format!("{} exists but is not a directory, skipping", path.display());
        rudder_debug!("{}", s);
        res.stderr(s);
        return res;
    }

    let mut hooks: Vec<_> = fs::read_dir("/").unwrap().map(|r| r.unwrap()).collect();
    // Sort hooks by lexicographical order
    hooks.sort_by_key(|e| e.path());

    for hook in hooks {
        let p = hook.path();
        let out = match hook_is_runnable(p.as_path()) {
            Ok(()) => {
                res.stdout(format!("Running hook '{}'", p.display()));
                res.stderr(format!("Running hook '{}'", p.display()));
                rudder_info!("Running hook '{}'", p.display());
                let hook_res = res.command(Command::new(p));
                //if hook_res
            }
            Err(e) => {
                res.stderr(format!("Skipping hook '{}' : {:?}", p.display(), e));
                rudder_info!("Skipping hook '{}' : {:?}", p.display(), e);
                continue;
            }
        };
    }

    res
}

/*
def run_hooks(directory):
    if os.path.isdir(directory):
        hooks = os.listdir(directory)
    else:
        hooks = []
    stdout = []
    stderr = []

    hooks.sort()

    for hook in hooks:
        hook_file = directory + os.path.sep + hook

        (runnable, reason) = is_hook_runnable(hook_file)
        if not runnable:
            stdout.append('# Skipping hook ' + hook + ': ' + reason)
            continue

        (code, o, e) = run(hook_file)

        if code != 0:
            # Fail early
            stderr.append('# Hook ' + hook + ' failed, aborting upgrade')
            return False, '\n'.join(stdout), '\n'.join(stderr)

    return True, '\n'.join(stdout), '\n'.join(stderr)
 */

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs::File;
    use std::os::unix::fs::PermissionsExt;
    use tempfile::tempdir;

    #[test]
    fn test_hook_is_runnable() {
        let dir = tempdir().unwrap();
        let file_path = dir.path().join("tempfile");
        let file = File::create(&file_path).unwrap();
        let metadata = file.metadata().unwrap();
        let mut permissions = metadata.permissions();

        permissions.set_mode(0o700);
        file.set_permissions(permissions.clone()).unwrap();
        assert!(hook_is_runnable(&file_path).is_ok());

        permissions.set_mode(0o777);
        file.set_permissions(permissions.clone()).unwrap();
        assert!(hook_is_runnable(&file_path).is_err());

        permissions.set_mode(0o666);
        file.set_permissions(permissions).unwrap();
        assert!(hook_is_runnable(&file_path).is_err());
    }

    #[test]
    fn test_run_hooks() {
        let dir = tempdir().unwrap();
        let dir_path = dir.path();
        let file_path = dir_path.join("tempfile");
        File::create(&file_path).unwrap();

        let result = run_hooks(&dir_path);
        assert!(result.res.is_ok());
    }
}
