// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS
//! Chocolatey CLI commands.
//!
//! Each command is a type implementing [`ChocoCommand`]: it knows the arguments to
//! pass to `choco`, which exit codes mean success, and how to parse its standard
//! output into a typed result. Commands are run with [`ChocoRunner::run`], which
//! checks the exit code before calling [`ChocoCommand::parse`].
//!
//! Read commands ([`ListInstalledPackages`], [`ListOutdatedPackages`],
//! [`ListPinnedPackages`]) use `--limit-output`, which makes choco print one
//! machine-readable `|`-separated line per package, with no banner.
//!
//! Write commands ([`InstallPackage`], [`UninstallPackage`]) handle one package
//! per call, because choco cannot install several packages at different versions
//! in a single call.
//!
//! [`ChocoRunner::run`]: crate::choco::ChocoRunner::run
use crate::choco::{AvailablePackage, ChocoVersion, InstalledPackage};
use crate::parameters::RudderPackageVersion;
use anyhow::Result;
use std::str::FromStr;

/// A chocolatey CLI command, with its own output type.
///
/// Implementors only describe the command. Running it is done by
/// [`ChocoRunner::run`](crate::choco::ChocoRunner::run).
pub trait ChocoCommand {
    /// Type created from the command execution stdout
    type Output;

    /// CLI arguments to pass to the `choco` command
    fn args(&self) -> Vec<String>;

    /// Successful exit codes of the `choco` command
    fn success_codes(&self) -> Vec<i32> {
        vec![0]
    }

    /// Compute `Output` type from the command stdout
    fn parse(&self, stdout: &str) -> Result<Self::Output>;
}

/// `choco --version` command
#[derive(Debug)]
pub struct Version;

impl ChocoCommand for Version {
    type Output = ChocoVersion;

    fn args(&self) -> Vec<String> {
        vec!["--version".into()]
    }

    fn parse(&self, stdout: &str) -> Result<Self::Output> {
        ChocoVersion::from_str(stdout)
    }
}

/// `choco list` command
#[derive(Debug)]
pub struct ListInstalledPackages;

impl ChocoCommand for ListInstalledPackages {
    type Output = Vec<InstalledPackage>;

    fn args(&self) -> Vec<String> {
        vec!["list".into(), "--limit-output".into()]
    }

    /// Only parse the lines containing a `|` in the output
    fn parse(&self, stdout: &str) -> Result<Self::Output> {
        stdout
            .lines()
            .filter(|l| l.contains('|'))
            .map(|line| InstalledPackage::from_str(line.trim()))
            .collect()
    }
}

/// `choco outdated` command
#[derive(Debug)]
pub struct ListOutdatedPackages;

impl ChocoCommand for ListOutdatedPackages {
    type Output = Vec<AvailablePackage>;

    fn args(&self) -> Vec<String> {
        vec!["outdated".into(), "--limit-output".into()]
    }

    /// Only parse the lines containing a `|` in the output
    fn parse(&self, stdout: &str) -> Result<Self::Output> {
        stdout
            .lines()
            .filter(|l| l.contains('|'))
            .map(|line| AvailablePackage::from_str(line.trim()))
            .collect()
    }
}

/// `choco pin list` command
#[derive(Debug)]
pub struct ListPinnedPackages;

impl ChocoCommand for ListPinnedPackages {
    type Output = Vec<InstalledPackage>;

    fn args(&self) -> Vec<String> {
        vec!["pin".into(), "list".into(), "--limit-output".into()]
    }

    /// Only parse the lines containing a `|` in the output
    fn parse(&self, stdout: &str) -> Result<Self::Output> {
        stdout
            .lines()
            .filter(|l| l.contains('|'))
            .map(|line| InstalledPackage::from_str(line.trim()))
            .collect()
    }
}

/// `choco install` command
/// Chocolatey does not support the installation of multiple packages with different versions in one
/// CLI call.
/// The upgrade command is an enhanced version of the install command, that upgrade the package to
/// the latest available version if already installede
#[derive(Debug)]
pub struct InstallPackage {
    name: String,
    version: RudderPackageVersion,
    extra_params: Vec<String>,
}

impl InstallPackage {
    pub fn new(
        name: &str,
        version: RudderPackageVersion,
        extra_params: Vec<String>,
    ) -> Result<Self> {
        Ok(Self {
            name: name.to_string(),
            extra_params,
            version,
        })
    }
}

impl ChocoCommand for InstallPackage {
    type Output = ();

    fn args(&self) -> Vec<String> {
        let mut args = match &self.version {
            RudderPackageVersion::Any => vec!["install".into(), self.name.clone()],
            RudderPackageVersion::Latest => vec!["upgrade".into(), self.name.clone()],
            RudderPackageVersion::Exact(v) => {
                vec![
                    "upgrade".into(),
                    self.name.clone(),
                    "--version".into(),
                    v.clone(),
                ]
            }
        };
        // Force chocolatey to exit with either 0 or 1
        args.extend(
            [
                "--ignore-package-exit-codes",
                "--yes",
                "--no-progress",
                "--limit-output",
            ]
            .map(String::from),
        );
        // Add user parameters if any
        args.extend(self.extra_params.iter().map(String::from));
        args
    }

    fn parse(&self, _: &str) -> Result<Self::Output> {
        Ok(())
    }
}

#[derive(Debug)]
pub struct UninstallPackage {
    name: String,
    version: RudderPackageVersion,
    extra_params: Vec<String>,
}

impl UninstallPackage {
    pub fn new(
        name: &str,
        version: RudderPackageVersion,
        extra_params: Vec<String>,
    ) -> Result<Self> {
        Ok(Self {
            name: name.to_string(),
            extra_params,
            version,
        })
    }
}

impl ChocoCommand for UninstallPackage {
    type Output = ();

    fn args(&self) -> Vec<String> {
        let mut args = vec!["uninstall".into(), self.name.clone()];
        if let RudderPackageVersion::Exact(v) = &self.version {
            args.extend(["--version".into(), v.into()])
        };
        // Force chocolatey to exit with either 0 or 1
        args.extend(
            [
                "--ignore-package-exit-codes",
                "--yes",
                "--no-progress",
                "--limit-output",
            ]
            .map(String::from),
        );
        // Add user parameters if any
        args.extend(self.extra_params.iter().map(String::from));
        args
    }

    fn parse(&self, _: &str) -> Result<Self::Output> {
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use RudderPackageVersion::{Any, Exact, Latest};

    #[test]
    fn install_must_build_correct_cli() {
        let a = InstallPackage::new("a", Any, vec![]).unwrap();
        assert_eq!(
            a.args(),
            vec![
                "install",
                "a",
                "--ignore-package-exit-codes",
                "--yes",
                "--no-progress",
                "--limit-output"
            ]
        );

        let b = InstallPackage::new(
            "b",
            Latest,
            ["--params", "'/GitAndUnixToolsOnPath /NoAutoCrlf'"]
                .iter()
                .map(|x| x.to_string())
                .collect(),
        )
        .unwrap();
        assert_eq!(
            b.args(),
            vec![
                "upgrade",
                "b",
                "--ignore-package-exit-codes",
                "--yes",
                "--no-progress",
                "--limit-output",
                "--params",
                "'/GitAndUnixToolsOnPath /NoAutoCrlf'"
            ]
        );

        let c = InstallPackage::new(
            "c",
            Exact("9.2.3-beta".to_string()),
            [
                "--params",
                "'/GitAndUnixToolsOnPath /NoAutoCrlf'",
                "--source",
                "https://somewhere/out/there",
            ]
            .iter()
            .map(|x| x.to_string())
            .collect(),
        )
        .unwrap();
        assert_eq!(
            c.args(),
            vec![
                "upgrade",
                "c",
                "--version",
                "9.2.3-beta",
                "--ignore-package-exit-codes",
                "--yes",
                "--no-progress",
                "--limit-output",
                "--params",
                "'/GitAndUnixToolsOnPath /NoAutoCrlf'",
                "--source",
                "https://somewhere/out/there"
            ]
        );
    }

    #[test]
    fn parses_list_of_installed_packages() {
        let s = "chocolatey|2.7.5
Pester|6.2.0
vim|9.2.1364
";
        let expected = vec![
            InstalledPackage {
                name: "chocolatey".to_string(),
                version: "2.7.5".to_string(),
            },
            InstalledPackage {
                name: "Pester".to_string(),
                version: "6.2.0".to_string(),
            },
            InstalledPackage {
                name: "vim".to_string(),
                version: "9.2.1364".to_string(),
            },
        ];
        let ip = ListInstalledPackages;
        assert_eq!(ip.parse(s).unwrap(), expected)
    }

    #[test]
    fn parses_list_of_outdated_packages() {
        let s = "vim|9.2.1364|9.2.1393|false";
        let expected = vec![AvailablePackage {
            name: "vim".to_string(),
            current_version: "9.2.1364".to_string(),
            available_version: "9.2.1393".to_string(),
            pinned: false,
        }];
        let ip = ListOutdatedPackages;
        assert_eq!(ip.parse(s).unwrap(), expected)
    }
}
