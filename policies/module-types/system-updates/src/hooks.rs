// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2024 Normation SAS

use crate::output::ResultOutput;
use anyhow::Result;
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

/*

def is_hook_runnable(path):
    if not os.access(path, os.X_OK):
        # Not executable
        return False, 'not executable'
    st = os.stat(path)
    if st.st_mode & 0o2:
        # "others" have write access
        return False, 'writable by everyone'
    if st.st_uid != os.getuid():
        # Not owned by current user
        return False, 'not owned by current user'
    return True, None

 */

fn hook_is_runnable(path: &Path) -> Result<()> {
    let metadata = path.metadata()?;
    let user = metadata.uid();
    let group = metadata.gid();
    let perms = metadata.permissions();
    let mode = perms.mode();

    Ok(())
}

fn run_hooks(path: &Path) -> ResultOutput<()> {
    let mut res = ResultOutput::builder();

    if !path.exists() {
        let s = format!(
            "The hook directory {} does not exist, skipping",
            path.display()
        );
        rudder_debug!("{}", s);
        res.stderr(s);
        return res.build(());
    }
    if !path.is_dir() {
        let s = format!("{} exists but is not a directory, skipping", path.display());
        rudder_debug!("{}", s);
        res.stderr(s);
        return res.build(());
    }

    let mut hooks: Vec<_> = fs::read_dir("/")?.map(|r| r.unwrap()).collect();
    // Sort hooks by lexicographical order
    hooks.sort_by_key(|e| e.path());

    for hook in hooks {
        let p = hook.path();
        let out = match hook_is_runnable(p.as_path()) {
            Ok(()) => Command::new(p).output()?,
            Err(e) => {
                res.stderr(format!("Skipping hook '{}' : {:?}", p.display(), e));
                rudder_info!("Skipping hook '{}' : {:?}", p.display(), e);
                continue;
            }
        };
    }

    res.build(())
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
        # Store the name of the hook in the output
        step = '# Running hook ' + hook
        stdout.append(step)
        stdout.append(o)
        stderr.append(step)
        stderr.append(e)

        if code != 0:
            # Fail early
            stderr.append('# Hook ' + hook + ' failed, aborting upgrade')
            return False, '\n'.join(stdout), '\n'.join(stderr)

    return True, '\n'.join(stdout), '\n'.join(stderr)
 */
