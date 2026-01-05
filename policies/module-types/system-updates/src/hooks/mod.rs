// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

use crate::output::{CommandBehavior, CommandCapture, ResultOutput};
use anyhow::Result;
use rudder_module_type::{rudder_debug, rudder_error, rudder_info};
use std::path::{Path, PathBuf};
use std::process::Command;
use std::{fmt, fs};

#[cfg(unix)]
mod unix;
#[cfg(windows)]
mod windows;

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
pub trait RunHooks: ToString {
    fn run(&self) -> ResultOutput<()> {
        let path = self.hooks_dir().join(self.to_string());
        self.run_dir(path.as_path())
    }
    fn hooks_dir(&self) -> PathBuf;
    fn is_runnable(&self, path: &Path) -> Result<()>;

    fn create_command(&self, p: &Path) -> Command;
    fn run_dir(&self, path: &Path) -> ResultOutput<()> {
        let mut res = ResultOutput::new(Ok(()));
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
            let cmd = self.create_command(&p);
            match self.is_runnable(p.as_path()) {
                Ok(()) => {
                    res.stdout(format!("Running hook '{}'", p.display()));
                    res.stderr(format!("Running hook '{}'", p.display()));
                    rudder_info!("Running hook '{}'", p.display());
                    let hook_res = ResultOutput::command(
                        cmd,
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
