// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

use anyhow::{Context, Error, Result, anyhow, bail};
use regex::Regex;
use std::{path::PathBuf, process::Command, str::FromStr};
use tracing::debug;

pub mod commands;
use commands::ChocoCommand;

#[derive(Debug, PartialEq, PartialOrd)]
pub struct InstalledPackage {
    pub name: String,
    pub version: String,
}

impl FromStr for InstalledPackage {
    type Err = Error;
    fn from_str(s: &str) -> Result<Self> {
        let (name, version) = s
            .split_once('|')
            .ok_or_else(|| anyhow!("expected 'name|version', got '{s}'"))?;
        Ok(Self {
            name: name.trim().to_string(),
            version: version.trim().to_string(),
        })
    }
}

#[derive(Debug, PartialEq, PartialOrd)]
pub struct AvailablePackage {
    pub name: String,
    pub current_version: String,
    pub available_version: String,
    pub pinned: bool,
}

impl FromStr for AvailablePackage {
    type Err = Error;

    fn from_str(s: &str) -> Result<Self> {
        let fields: Vec<&str> = s.split('|').map(str::trim).collect();
        let [name, current_version, available_version, pinned] = fields.as_slice() else {
            bail!("expected 'name|current version|available version|pinned', got '{s}'");
        };
        Ok(Self {
            name: name.to_string(),
            current_version: current_version.to_string(),
            available_version: available_version.to_string(),
            pinned: pinned
                .to_lowercase()
                .parse()
                .with_context(|| format!("invalid pinned value '{pinned}' in '{s}'"))?,
        })
    }
}

#[derive(Debug)]
pub struct ChocoRunner {
    exe: PathBuf,
}

impl Default for ChocoRunner {
    fn default() -> Self {
        Self::new()
    }
}

impl ChocoRunner {
    pub fn new() -> Self {
        Self {
            exe: PathBuf::from("choco"),
        }
    }

    pub fn run<C: ChocoCommand>(&self, command: &C) -> Result<C::Output> {
        let args = command.args();
        let mut cmd = Command::new(&self.exe);
        cmd.args(&args);
        debug!("Execute {:?}", cmd);
        let output = cmd
            .output()
            .with_context(|| format!("failed to execute {}", self.exe.display()))?;
        let stdout = String::from_utf8_lossy(&output.stdout);
        let stderr = String::from_utf8_lossy(&output.stderr);
        let code = output.status.code();
        debug!(
            "Result: exit code {:?}\nstdout: {}\nstderr: {}",
            output.status.code(),
            stdout.trim(),
            stderr.trim()
        );
        if !code.is_some_and(|c| command.success_codes().contains(&c)) {
            bail!(
                "`{} {}` failed (exit code {:?})\nstdout: {}\nstderr: {}",
                self.exe.display(),
                args.join(" "),
                code,
                stdout.trim(),
                stderr.trim()
            );
        }
        command
            .parse(&stdout)
            .with_context(|| format!("could not parse output of `choco {}`", args.join(" ")))
    }
}

#[derive(Debug, PartialEq)]
pub struct ChocoVersion {
    major: u32,
    minor: u32,
    patch: u32,
}

impl ChocoVersion {
    pub fn new(major: u32, minor: u32, patch: u32) -> Self {
        Self {
            major,
            minor,
            patch,
        }
    }
}

impl FromStr for ChocoVersion {
    type Err = Error;
    fn from_str(s: &str) -> Result<Self> {
        let t = s.trim();
        let pattern = Regex::new(r"^(?<major>\d+)\.(?<minor>\d+)\.(?<patch>\d+).*$")?;
        let (_, [major, minor, patch]) = pattern
            .captures(t)
            .with_context(|| format!("could not parse the choco version '{s:?}'"))?
            .extract();
        Ok(ChocoVersion {
            major: major
                .parse()
                .with_context(|| format!("Invalid major version '{}'", major))?,
            minor: minor
                .parse()
                .with_context(|| format!("Invalid minor version '{}'", minor))?,
            patch: patch
                .parse()
                .with_context(|| format!("Invalid minor version '{}'", patch))?,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_chocolatey_version() {
        assert_eq!(
            ChocoVersion::from_str("1.2.3-beta").unwrap(),
            ChocoVersion::new(1, 2, 3)
        );
        assert_eq!(
            ChocoVersion::from_str("1.2.3").unwrap(),
            ChocoVersion::new(1, 2, 3)
        );
        assert_eq!(
            ChocoVersion::from_str("12.34.5test").unwrap(),
            ChocoVersion::new(12, 34, 5)
        );
        assert!(ChocoVersion::from_str("12.34").is_err());
    }

    #[test]
    fn parses_name_and_version() {
        let pkg: InstalledPackage = "openssl|3.0.2".parse().unwrap();
        assert_eq!(pkg.name, "openssl");
        assert_eq!(pkg.version, "3.0.2");
    }

    #[test]
    fn trims_whitespace() {
        let pkg: InstalledPackage = "  curl | 8.5.0 \n".parse().unwrap();
        assert_eq!(pkg.name, "curl");
        assert_eq!(pkg.version, "8.5.0");
    }

    #[test]
    fn rejects_missing_separator() {
        let result = "no-separator-here".parse::<InstalledPackage>();
        assert!(result.is_err());
    }

    #[test]
    fn error_message_mentions_input() {
        let err = "bad".parse::<InstalledPackage>().unwrap_err();
        assert!(err.to_string().contains("bad"));
    }

    #[test]
    fn parses_outdated_line() {
        let expected = AvailablePackage {
            name: "git".into(),
            current_version: "2.46.0".into(),
            available_version: "2.47.0".into(),
            pinned: false,
        };
        assert_eq!(
            "git|2.46.0|2.47.0|false"
                .parse::<AvailablePackage>()
                .unwrap(),
            expected
        );
        assert_eq!(
            "git|2.46.0|2.47.0|False"
                .parse::<AvailablePackage>()
                .unwrap(),
            expected
        );
        assert!(
            "vlc|3.0.20|3.0.21|true"
                .parse::<AvailablePackage>()
                .unwrap()
                .pinned
        );
        assert!(
            "vlc|3.0.20|3.0.21|True"
                .parse::<AvailablePackage>()
                .unwrap()
                .pinned
        );
    }

    #[test]
    fn rejects_wrong_field_count() {
        assert!("git|2.46.0|2.47.0".parse::<AvailablePackage>().is_err());
        assert!(
            "git|2.46.0|2.47.0|false|extra"
                .parse::<AvailablePackage>()
                .is_err()
        );
    }

    #[test]
    fn rejects_invalid_pinned() {
        let err = "git|2.46.0|2.47.0|maybe"
            .parse::<AvailablePackage>()
            .unwrap_err();
        assert!(format!("{err:#}").contains("maybe"));
    }
}
