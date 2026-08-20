// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

//! Node properties provided by local inventory hooks.
//!
//! Each executable in the hook directory is run, and is expected to print a JSON object on its
//! standard output. The objects are collected into the JSON array the server reads from the
//! `CUSTOM_PROPERTIES` element.
//!
//! We run these as root, so a hook we are not sure about is skipped rather than executed:
//! anyone able to plant or modify a file here would otherwise get our privileges. See
//! `Hook::validate` for the conditions a hook has to meet.

use std::{
    fs,
    os::unix::fs::MetadataExt,
    path::{Path, PathBuf},
    process::{Command, Stdio},
    str,
    time::Duration,
};

use anyhow::{Context, Result, bail};
use tracing::{debug, instrument, warn};
use wait_timeout::ChildExt;

/// Maximal execution time of a single hook.
const TIMEOUT: Duration = Duration::from_secs(300);

/// Maximal size of the output we read from a hook, to bound our memory usage.
const MAX_OUTPUT_SIZE: usize = 5 * 1024 * 1024;

/// A hook we have checked we can safely execute.
///
/// The only way to build one is `Hook::validate`, so holding a value of this type is the
/// proof the checks were made and passed.
#[derive(Debug, PartialEq)]
pub struct Hook(PathBuf);

impl Hook {
    /// Checks a directory entry is a hook we accept to run as root, and returns it if so.
    ///
    /// The executable bit is what [`entries`] selects on, so everything reaching here is meant
    /// to run and a condition it does not meet is worth reporting.
    ///
    /// Anything we cannot check, we refuse to run.
    fn validate(path: &Path) -> Result<Self> {
        // `symlink_metadata` does not follow symlinks, so a symlink is reported as such
        // instead of us checking the permissions of a file we may not be the one running.
        let metadata = fs::symlink_metadata(path)
            .with_context(|| format!("Reading metadata of '{}'", path.display()))?;

        if !metadata.is_file() {
            bail!("'{}' is not a regular file", path.display());
        }
        let mode = metadata.mode();
        let owner = metadata.uid();
        let current_user = nix::unistd::geteuid().as_raw();
        if owner != 0 && owner != current_user {
            bail!(
                "'{}' is owned by neither root nor the current user (owner uid is {owner})",
                path.display()
            );
        }
        if mode & 0o022 != 0 {
            bail!(
                "'{}' is writable by its group or by everyone (mode is {:o})",
                path.display(),
                mode & 0o777
            );
        }
        Ok(Self(path.to_path_buf()))
    }

    /// Runs the hook and returns the JSON value it printed.
    fn run(&self) -> Result<serde_json::Value> {
        self.run_with_timeout(TIMEOUT)
    }

    #[instrument(level = "debug", name = "hook", skip(self), fields(path = %self.0.display()))]
    fn run_with_timeout(&self, timeout: Duration) -> Result<serde_json::Value> {
        debug!("Running inventory hook '{}'", self.0.display());
        let mut child = Command::new(&self.0)
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .with_context(|| format!("Running inventory hook '{}'", self.0.display()))?;

        // A hook writing more than the pipe can buffer blocks until we read it, and is
        // killed by the timeout below. Its output would be incomplete, hence invalid JSON,
        // so we never act on a partial value.
        match child.wait_timeout(timeout)? {
            Some(status) if status.success() => (),
            Some(status) => bail!("Inventory hook '{}' failed with {status}", self.0.display()),
            None => {
                child.kill().context("Killing timed out inventory hook")?;
                child
                    .wait()
                    .context("Waiting for timed out inventory hook")?;
                bail!(
                    "Inventory hook '{}' timed out after {:?}",
                    self.0.display(),
                    timeout
                )
            }
        }

        let out = child
            .wait_with_output()
            .context("Reading inventory hook output")?;
        if out.stdout.len() > MAX_OUTPUT_SIZE {
            bail!(
                "Inventory hook '{}' returned more than {MAX_OUTPUT_SIZE} bytes",
                self.0.display()
            );
        }
        let stdout = str::from_utf8(&out.stdout)
            .with_context(|| format!("Non-UTF-8 output from '{}'", self.0.display()))?;
        serde_json::from_str(stdout)
            .with_context(|| format!("Invalid JSON returned by '{}'", self.0.display()))
    }
}

/// Runs the hooks of the given directory, in file name order.
///
/// Returns the JSON array to report, or `None` when there is no hook directory at all, to
/// leave the element out of the inventory like FusionInventory does.
///
/// A hook we refuse to run, that fails, or that does not return JSON only makes us skip its
/// properties: the properties of the other hooks are still reported.
pub fn custom_properties(dir: &Path) -> Option<String> {
    if !dir.is_dir() {
        debug!("No inventory hook directory at '{}'", dir.display());
        return None;
    }
    let mut properties: Vec<serde_json::Value> = vec![];
    for path in entries(dir) {
        match Hook::validate(&path).and_then(|h| h.run()) {
            Ok(value) => properties.push(value),
            Err(e) => warn!("Skipping inventory hook: {e:#}"),
        }
    }
    debug!("Collected the properties of {} hooks", properties.len());
    // Serializing the array normalizes the formatting of what the hooks printed, and leaves
    // building valid JSON to `serde_json` rather than to string concatenation.
    Some(serde_json::Value::Array(properties).to_string())
}

/// The entries of a hook directory, in file name order, ignoring hidden files and files
/// without an executable bit.
fn entries(dir: &Path) -> Vec<PathBuf> {
    let read_dir = match fs::read_dir(dir) {
        Ok(read_dir) => read_dir,
        Err(e) => {
            warn!(
                "Could not read the hook directory '{}', reporting no property: {e}",
                dir.display()
            );
            return vec![];
        }
    };
    let mut paths: Vec<PathBuf> = read_dir
        .filter_map(|e| e.ok())
        .filter(|e| !e.file_name().to_string_lossy().starts_with('.'))
        .filter(is_executable)
        .map(|e| e.path())
        .collect();
    paths.sort();
    paths
}

/// Whether a directory entry carries an executable bit.
///
/// `DirEntry::metadata` does not follow symlinks, so this is the mode of the entry itself, as in
/// [`Hook::validate`]. An entry we cannot stat is reported as executable, so that it reaches
/// `validate` and is refused there with the reason.
fn is_executable(entry: &fs::DirEntry) -> bool {
    match entry.metadata() {
        Ok(metadata) => {
            let executable = metadata.mode() & 0o111 != 0;
            if !executable {
                debug!(
                    "Ignoring '{}', which is not executable",
                    entry.path().display()
                );
            }
            executable
        }
        Err(_) => true,
    }
}

#[cfg(test)]
mod tests {
    use std::os::unix::fs::PermissionsExt;

    use pretty_assertions::assert_eq;
    use tempfile::tempdir;

    use super::*;
    use crate::util::no_concurrent_fork;

    /// Writes a hook with the given shell body and permissions, in the directory of the test
    /// that asked for it, so that no two tests share a program.
    ///
    /// `fs::write` closes the file before returning, which it has to be before we execute it,
    /// see [`no_concurrent_fork`].
    fn write_hook(dir: &Path, name: &str, body: &str, mode: u32) -> PathBuf {
        let path = dir.join(name);
        fs::write(&path, format!("#!/bin/sh\n{body}\n")).unwrap();
        fs::set_permissions(&path, fs::Permissions::from_mode(mode)).unwrap();
        path
    }

    /// Writes a hook printing the given output, with the given permissions.
    fn hook(dir: &Path, name: &str, output: &str, mode: u32) -> PathBuf {
        write_hook(dir, name, &format!("printf '%s' '{output}'"), mode)
    }

    #[test]
    fn it_returns_none_without_a_hook_directory() {
        let dir = tempdir().unwrap();
        assert_eq!(custom_properties(&dir.path().join("absent")), None);
    }

    #[test]
    fn it_returns_an_empty_array_for_an_empty_directory() {
        let dir = tempdir().unwrap();
        assert_eq!(custom_properties(dir.path()), Some("[]".to_string()));
    }

    #[test]
    fn it_collects_hooks_in_file_name_order() {
        let _guard = no_concurrent_fork();
        let dir = tempdir().unwrap();
        hook(
            dir.path(),
            "20-second",
            r#"{"name":"b","value":"2"}"#,
            0o700,
        );
        hook(dir.path(), "10-first", r#"{ "name": "a" }"#, 0o755);
        assert_eq!(
            custom_properties(dir.path()),
            Some(r#"[{"name":"a"},{"name":"b","value":"2"}]"#.to_string())
        );
    }

    #[test]
    fn it_skips_hooks_it_cannot_trust_or_run() {
        let _guard = no_concurrent_fork();
        let dir = tempdir().unwrap();
        hook(dir.path(), "10-ok", r#"{"name":"a"}"#, 0o700);
        hook(dir.path(), "20-not-executable", r#"{"name":"b"}"#, 0o600);
        hook(dir.path(), "30-world-writable", r#"{"name":"c"}"#, 0o707);
        hook(dir.path(), "40-group-writable", r#"{"name":"d"}"#, 0o770);
        hook(dir.path(), "50-not-json", "definitely not json", 0o700);
        hook(dir.path(), ".60-hidden", r#"{"name":"e"}"#, 0o700);
        // A failing hook, whose output must not be used either.
        write_hook(
            dir.path(),
            "70-failing",
            r#"printf '%s' '{"name":"f"}'; exit 1"#,
            0o700,
        );

        assert_eq!(
            custom_properties(dir.path()),
            Some(r#"[{"name":"a"}]"#.to_string())
        );
    }

    #[test]
    fn it_refuses_to_run_a_symlink() {
        let dir = tempdir().unwrap();
        let target = hook(dir.path(), "target", r#"{"name":"a"}"#, 0o700);
        let link = dir.path().join("10-link");
        std::os::unix::fs::symlink(&target, &link).unwrap();
        assert!(Hook::validate(&link).is_err());
    }

    #[test]
    fn it_refuses_to_run_a_directory() {
        let dir = tempdir().unwrap();
        let sub = dir.path().join("10-dir");
        fs::create_dir(&sub).unwrap();
        assert!(Hook::validate(&sub).is_err());
    }

    #[test]
    fn it_kills_a_hook_that_times_out() {
        let _guard = no_concurrent_fork();
        let dir = tempdir().unwrap();
        // Sleeps far longer than the timeout we pass below.
        let path = write_hook(dir.path(), "10-slow", "sleep 30", 0o700);

        let hook = Hook::validate(&path).unwrap();
        let start = std::time::Instant::now();
        let err = hook
            .run_with_timeout(Duration::from_millis(200))
            .unwrap_err();
        assert!(err.to_string().contains("timed out"), "{err}");
        assert!(start.elapsed() < Duration::from_secs(5));
    }
}
