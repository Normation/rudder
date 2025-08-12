// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

use std::{process::Command, str};

use serde::{Deserialize, Serialize};
use tracing::{debug, warn};
use which::which;

use crate::cmd::CmdOutput;

#[derive(Serialize, Deserialize, PartialEq, Eq, Debug, Clone)]
pub struct Dependencies {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub python: Option<Vec<PythonDependency>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub binary: Option<Vec<BinaryDependency>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub apt: Option<Vec<AptDependency>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub rpm: Option<Vec<RpmDependency>>,
}

#[derive(Serialize, Deserialize, PartialEq, Eq, Debug, Clone)]
pub struct BinaryDependency(String);

#[derive(Serialize, Deserialize, PartialEq, Eq, Debug, Clone)]
pub struct PythonDependency(String);

#[derive(Serialize, Deserialize, PartialEq, Eq, Debug, Clone)]
pub struct AptDependency(String);

#[derive(Serialize, Deserialize, PartialEq, Eq, Debug, Clone)]
pub struct RpmDependency(String);

impl Dependencies {
    pub fn are_installed(&self) -> bool {
        if let Some(v) = &self.python
            && !v.iter().all(|x| x.is_installed())
        {
            return false;
        };
        if let Some(v) = &self.binary
            && !v.iter().all(|x| x.is_installed())
        {
            return false;
        };
        if let Some(v) = &self.apt
            && which("apt").is_ok()
            && !v.iter().all(|x| x.is_installed())
        {
            return false;
        };
        if let Some(v) = &self.rpm
            && which("rpm").is_ok()
            && !v.iter().all(|x| x.is_installed())
        {
            return false;
        };
        true
    }
}

pub trait IsInstalled {
    fn is_installed(&self) -> bool;
}

impl IsInstalled for PythonDependency {
    fn is_installed(&self) -> bool {
        warn!(
            "Deprecated dependency type 'python' with value '{}'. It is up to you to make sure it is installed, ignoring.",
            self.0
        );
        true
    }
}

impl IsInstalled for AptDependency {
    fn is_installed(&self) -> bool {
        let mut binding = Command::new("dpkg-query");
        // # dpkg-query --show --showformat='${Status}' rudder-api-client
        // install ok installed
        let cmd = binding
            .arg("--show")
            .arg("--showformat='${Status}'")
            .arg("--")
            .arg(&self.0);
        // Retrieve package status
        let package_status_output = match CmdOutput::new(cmd) {
            Ok(a) => a,
            Err(e) => {
                warn!(
                    "Could not check 'apt' base dependency, most likely because apt is not installed:\n{}",
                    e
                );
                return false;
            }
        };
        debug!("Package status output: {}", package_status_output);
        let found =
            String::from_utf8_lossy(&package_status_output.output.stdout).contains("installed");
        if !found {
            warn!("Could not find 'apt' base dependency '{}'", self.0);
        } else {
            debug!("'apt' base dependency '{}' found on the system", self.0);
        }
        found
    }
}

impl IsInstalled for RpmDependency {
    fn is_installed(&self) -> bool {
        let mut binding = Command::new("rpm");
        let cmd = binding.arg("-q").arg("--").arg(&self.0);
        let result = match CmdOutput::new(cmd) {
            Ok(a) => a,
            Err(e) => {
                warn!(
                    "Could not check for 'rpm' base dependency, most likely because rpm is not installed,\n{}",
                    e
                );
                return false;
            }
        };
        if !result.output.status.success() {
            warn!("Could not find 'rpm' base dependency '{}'", self.0);
        } else {
            debug!("'rpm' base dependency '{}' found on the system", self.0);
        }
        result.output.status.success()
    }
}

impl IsInstalled for BinaryDependency {
    fn is_installed(&self) -> bool {
        match which(&self.0) {
            Ok(_) => {
                debug!("'binary' base dependency '{}' found on the system", self.0);
                true
            }
            Err(_) => {
                warn!("Could not find 'binary' base dependency '{}'", self.0);
                false
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use std::str::FromStr;

    use super::*;
    #[test]
    fn test_rpm_exec() {
        let a = RpmDependency(String::from_str("nonexistingpackage").unwrap());
        assert!(!a.is_installed());
    }
    #[test]
    fn test_apt_exec_failure() {
        let a = AptDependency(String::from_str("nonexistingpackage").unwrap());
        assert!(!a.is_installed());
    }
    #[test]
    fn test_binary_exec() {
        let a = BinaryDependency(String::from_str("cargo").unwrap());
        assert!(a.is_installed());
    }
    #[test]
    fn test_binary_exec_failure() {
        let a = BinaryDependency(String::from_str("caat").unwrap());
        assert!(!a.is_installed());
    }
}
