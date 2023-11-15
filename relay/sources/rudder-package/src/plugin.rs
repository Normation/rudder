// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2023 Normation SAS

use crate::archive;
use crate::versions;
use log::error;
use serde::{Deserialize, Serialize};
use std::process::Stdio;
use std::{collections::HashMap, process::Command, str};
use which::which;

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

impl Dependencies {
    pub fn are_installed(&self) -> bool {
        if let Some(v) = &self.python {
            if !v.into_iter().all(|x| x.is_installed()) {
                return false;
            }
        };
        if let Some(v) = &self.binary {
            if !v.into_iter().all(|x| x.is_installed()) {
                return false;
            }
        };
        if let Some(v) = &self.apt {
            if !v.into_iter().all(|x| x.is_installed()) {
                return false;
            }
        };
        if let Some(v) = &self.rpm {
            if !v.into_iter().all(|x| x.is_installed()) {
                return false;
            }
        };
        true
    }
}

pub trait IsInstalled {
    fn is_installed(&self) -> bool;
}

#[derive(Serialize, Deserialize, PartialEq, Eq, Debug, Clone)]
pub struct PythonDependency(String);
impl IsInstalled for PythonDependency {
    fn is_installed(&self) -> bool {
        error!("Deprecated dependency type 'python' with value '{}'. It is up to you to make sure it is installed, ignoring.", self.0);
        true
    }
}

#[derive(Serialize, Deserialize, PartialEq, Eq, Debug, Clone)]
pub struct AptDependency(String);
impl IsInstalled for AptDependency {
    fn is_installed(&self) -> bool {
        let failure_msg = format!(
            "Could not execute the dpkg command to check if the package '{}' is installed",
            self.0
        );
        let package_list = Command::new("dpkg")
            .arg("-l")
            .stdout(Stdio::piped())
            .spawn()
            .expect(&failure_msg);
        let package_list_out = package_list.stdout.expect("Failed to open dpkg stdout");
        let status = Command::new("grep")
            .arg(format!(r"-E '^ii\s+{}\s+.*'", self.0))
            .stdin(Stdio::from(package_list_out))
            .status()
            .expect(&failure_msg);
        if !status.success() {
            error!("Could not find 'apt' base dependency '{}'", self.0);
        }
        status.success()
    }
}

#[derive(Serialize, Deserialize, PartialEq, Eq, Debug, Clone)]
pub struct RpmDependency(String);
impl IsInstalled for RpmDependency {
    fn is_installed(&self) -> bool {
        let failure_msg = format!(
            "Could not execute the rpm command to check if the package '{}' is installed",
            self.0
        );
        let status = Command::new("sh")
            .arg("-c")
            .arg(format!("rpm -q {}", self.0))
            .status()
            .expect(&failure_msg);
        if !status.success() {
            error!("Could not find 'rpm' base dependency '{}'", self.0);
        }
        status.success()
    }
}

#[derive(Serialize, Deserialize, PartialEq, Eq, Debug, Clone)]
pub struct BinaryDependency(String);
impl IsInstalled for BinaryDependency {
    fn is_installed(&self) -> bool {
        match which(self.0.clone()) {
            Ok(_) => true,
            Err(_) => {
                error!("Could not find 'binary' base dependency '{}'", self.0);
                false
            }
        }
    }
}

#[derive(Serialize, Deserialize, PartialEq, Eq, Debug, Clone)]
pub struct Metadata {
    #[serde(rename = "type")]
    pub plugin_type: archive::PackageType,
    pub name: String,
    pub version: versions::ArchiveVersion,
    #[serde(rename(serialize = "build-date", deserialize = "build-date"))]
    pub build_date: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub depends: Option<Dependencies>,
    #[serde(rename(serialize = "build-commit", deserialize = "build-commit"))]
    pub build_commit: String,
    pub content: HashMap<String, String>,
    #[serde(rename(serialize = "jar-files", deserialize = "jar-files"))]
    #[serde(skip_serializing_if = "Option::is_none")]
    pub jar_files: Option<Vec<String>>,
}

impl Metadata {
    pub fn is_compatible(&self, webapp_version: &str) -> bool {
        self.version.rudder_version.is_compatible(webapp_version)
    }
}
