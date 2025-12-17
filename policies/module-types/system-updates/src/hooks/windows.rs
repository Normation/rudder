// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

use crate::hooks::{Hooks, RunHooks};
use anyhow::bail;
use std::path::{Path, PathBuf};
use std::process::Command;

pub const POWERSHELL_BIN: &str = "PowerShell.exe";
pub const POWERSHELL_OPTS: &[&str] = &["-NoProfile", "-NonInteractive"];

pub const HOOKS_FOLDER: &str = "C:/Program Files/Rudder/system-update/hooks.d";

impl RunHooks for Hooks {
    fn hooks_dir(&self) -> PathBuf {
        PathBuf::from(HOOKS_FOLDER)
    }

    fn is_runnable(&self, path: &Path) -> anyhow::Result<()> {
        if !path.is_file() {
            bail!(
                "{:?} is not a file and can not be executed as campaign hook",
                path
            )
        }
        Ok(())
    }

    fn create_command(&self, p: &Path) -> Command {
        let mut cmd = Command::new(POWERSHELL_BIN);
        cmd.args(POWERSHELL_OPTS)
            .arg("-Command")
            .arg(format!("&'{}'", p.display()));
        cmd
    }
}
