// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

use anyhow::{Context, Result, bail};
use rudder_module_type::{
    CheckApplyResult, ModuleTypeMetadata, PolicyMode, ProtocolResult, ValidateResult,
    parameters::Parameters,
};
use rudder_module_type::{ModuleType0, Outcome};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections;
use std::fmt;

use crate::choco::{AvailablePackage, ChocoRunner, InstalledPackage, commands};
use crate::parameters::{ChocoModuleParameters, PackageEntry};

pub const MODULE_NAME: &str = env!("CARGO_PKG_NAME");
pub const MODULE_FEATURES: [&str; 0] = [];

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AuditEntryOutcome {
    Compliant(String),
    NonCompliant(String),
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum EntryOutcome {
    Success(String),
    Repaired(String),
    NonCompliant(String),
    Error(String),
}

impl From<AuditEntryOutcome> for EntryOutcome {
    fn from(o: AuditEntryOutcome) -> Self {
        match o {
            AuditEntryOutcome::Compliant(s) => Self::Success(s),
            AuditEntryOutcome::NonCompliant(s) => Self::NonCompliant(s),
        }
    }
}

impl fmt::Display for EntryOutcome {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Success(s) | Self::Repaired(s) | Self::NonCompliant(s) | Self::Error(s) => {
                f.write_str(s)
            }
        }
    }
}

// TODO multiple versions installed case
pub struct PackageState {
    name: String,
    version: String,
    latest_available_version: Option<String>,
    _pinned_version: Option<()>,
}

pub struct ChocoModule {
    runner: ChocoRunner,
    state: Vec<PackageState>,
}

// TODO multiple versions installed case
impl ChocoModule {
    pub fn new() -> Result<Self> {
        let runner = ChocoRunner::default();
        let installed = runner.run(&commands::ListInstalledPackages)?;
        let outdated = runner.run(&commands::ListOutdatedPackages)?;
        let state = Self::compute_current_state(&installed, &outdated, &[]);
        Ok(Self { state, runner })
    }

    pub fn read_state(&self) -> Result<Vec<PackageState>> {
        let installed = self.runner.run(&commands::ListInstalledPackages)?;
        let outdated = self.runner.run(&commands::ListOutdatedPackages)?;
        Ok(Self::compute_current_state(&installed, &outdated, &[]))
    }

    fn compute_current_state(
        installed: &[InstalledPackage],
        outdated: &[AvailablePackage],
        _pinned: &[InstalledPackage],
    ) -> Vec<PackageState> {
        installed
            .iter()
            .map(|x| PackageState {
                name: x.name.clone(),
                version: x.version.clone(),
                latest_available_version: outdated
                    .iter()
                    .find(|y| x.name == y.name)
                    .map(|y| y.available_version.clone()),
                _pinned_version: None,
            })
            .collect()
    }

    fn get_entry_state(&self, e: &PackageEntry) -> Option<&PackageState> {
        self.state.iter().find(|x| x.name == e.name)
    }

    fn audit_entry(&self, e: &PackageEntry) -> AuditEntryOutcome {
        use crate::parameters::RudderPackageStatus::{Absent, Present};
        use crate::parameters::RudderPackageVersion::{Any, Exact, Latest};
        use AuditEntryOutcome::{Compliant, NonCompliant};
        let name = &e.name;
        let expected_version = &e.version;
        let expected_status = &e.status;
        match (&expected_status, &expected_version, self.get_entry_state(e)) {
            (Present, _, None) => NonCompliant(format!(
                "Package '{}' in version '{:?}' was not found installed",
                name, expected_version
            )),
            (Present, Any, Some(x)) => Compliant(format!(
                "Package '{}' was found installed in version '{}'",
                name, x.version
            )),
            (Present, Latest, Some(x)) => {
                if let Some(lv) = &x.latest_available_version {
                    NonCompliant(format!(
                        "Package '{}' was found installed in version '{}' when the latest available is '{}'",
                        name, x.version, lv
                    ))
                } else {
                    Compliant(format!(
                        "Package '{}' was found installed in version '{}' which is the latest available",
                        name, x.version
                    ))
                }
            }
            (Present, Exact(ev), Some(x)) => {
                if ev == &x.version {
                    Compliant(format!(
                        "Package '{}' was found installed in version '{}'",
                        name, x.version
                    ))
                } else {
                    NonCompliant(format!(
                        "Package '{}' was found installed in version '{}' when '{}' was required",
                        name, x.version, ev
                    ))
                }
            }
            (Absent, _, None) => Compliant(format!(
                "Package '{}' in version '{:?}' was not found installed",
                name, expected_version
            )),
            (Absent, Any, Some(x)) => NonCompliant(format!(
                "Package '{}' was found installed in version '{}' when it was required to be absent whatever the version",
                name, x.version
            )),
            (Absent, Latest, Some(x)) => {
                if let Some(lv) = &x.latest_available_version {
                    Compliant(format!(
                        "Package '{}' was found installed in version '{}' when the version '{}' is available, and the latest available version was required to be absent",
                        name, x.version, lv
                    ))
                } else {
                    NonCompliant(format!(
                        "Package '{}' was found installed in version '{}' which is the latest available, and the latest available version was required to be absent",
                        name, x.version
                    ))
                }
            }
            (Absent, Exact(ev), Some(x)) => {
                if ev == &x.version {
                    NonCompliant(format!(
                        "Package '{}' was found installed in version '{}' when it was required to be absent",
                        name, x.version
                    ))
                } else {
                    Compliant(format!(
                        "Package '{}' was found installed in version '{}' when the version '{}' was required to be absent",
                        name, x.version, ev
                    ))
                }
            }
        }
    }

    pub fn repair_entry(&self, entry: &PackageEntry) -> Result<EntryOutcome> {
        use crate::parameters::RudderPackageStatus::{Absent, Present};
        match entry.status {
            Absent => {
                let cmd = commands::UninstallPackage::new(&entry.name, entry.version.clone(), entry.extra_params.clone()).context(format!("An unexpected error occurred while building the chocolatey CLI command to uninstall package '{}'.", entry.name))?;
                Ok(EntryOutcome::Repaired(format!(
                    "Successfully unsinstalled package '{}'. {:?}",
                    entry.name,
                    self.runner.run(&cmd).context(format!(
                        "An unexpected error occurred while trying to uninstall package '{:?}'.",
                        entry
                    ))?
                )))
            }
            Present => {
                let cmd = commands::InstallPackage::new(&entry.name, entry.version.clone(), entry.extra_params.clone()).context(format!("An unexpected error occurred while building the chocolatey CLI command to install package '{}'.", entry.name))?;
                Ok(EntryOutcome::Repaired(format!(
                    "Successfully install package '{}'. {:?}",
                    entry.name,
                    self.runner.run(&cmd).context(format!(
                        "An unexpected error occurred while trying to install the package '{:?}'.",
                        entry
                    ))?
                )))
            }
        }
    }

    fn verify_repair(&self, entry: &PackageEntry, initial_outcome: EntryOutcome) -> EntryOutcome {
        let audit_post_outcome = self.audit_entry(entry);
        match (initial_outcome, audit_post_outcome) {
            (EntryOutcome::Success(_), AuditEntryOutcome::NonCompliant(after)) => {
                EntryOutcome::Error(format!(
                    "Package '{}' state was initially correct but its state changed after other packages were changed: {}",
                    entry.name, after
                ))
            }
            (EntryOutcome::Repaired(s), AuditEntryOutcome::NonCompliant(after)) => {
                EntryOutcome::Error(format!(
                    "{s}. Repair tentative failed. Post repair audit: {after}"
                ))
            }
            (io, _) => io,
        }
    }

    fn check_apply_inner(
        &mut self,
        policy_mode: PolicyMode,
        parameters: &ChocoModuleParameters,
    ) -> CheckApplyResult {
        let mut outcomes: Vec<EntryOutcome> = parameters
            .packages
            .iter()
            .map(|entry| match (policy_mode, self.audit_entry(entry)) {
                (PolicyMode::Enforce, AuditEntryOutcome::NonCompliant(_)) => {
                    match self.repair_entry(entry) {
                        Err(err) => EntryOutcome::Error(format!("{err:#}")),
                        Ok(r) => r,
                    }
                }
                (_, outcome) => outcome.into(),
            })
            .collect();
        // Post repaired audit if a change was tried
        if outcomes
            .iter()
            .any(|x| matches!(x, EntryOutcome::Repaired(_)))
        {
            self.state = self
                .read_state()
                .context("Could not list the chocolatey packages installed after the repair")?;
            outcomes = parameters
                .packages
                .iter()
                .zip(outcomes)
                .map(|(entry, outcome)| self.verify_repair(entry, outcome))
                .collect();
        };
        // Aggregate the results
        let msg = outcomes
            .iter()
            .map(|x| x.to_string())
            .collect::<Vec<_>>()
            .join("\n");
        if outcomes
            .iter()
            .any(|x| matches!(x, EntryOutcome::Repaired(_)))
        {
            Ok(Outcome::repaired(msg))
        } else {
            Ok(Outcome::success())
        }
    }
}

impl ModuleType0 for ChocoModule {
    fn metadata(&self) -> ModuleTypeMetadata {
        ModuleTypeMetadata::new(MODULE_NAME, Vec::from(MODULE_FEATURES))
    }

    fn init(&mut self) -> ProtocolResult {
        ProtocolResult::Success
    }

    fn validate(&self, parameters: &Parameters) -> ValidateResult {
        //Parse as parameters type
        let cmp: ChocoModuleParameters =
            serde_json::from_value(Value::Object(parameters.data.clone()))?;
        let mut seen = collections::HashSet::new();
        let duplicates: Vec<&str> = cmp
            .packages
            .iter()
            .map(|e| e.name.as_str())
            .filter(|name| !seen.insert(name.to_lowercase()))
            .collect();
        if !duplicates.is_empty() {
            bail!(
                "Each package can only be listed once, found duplicates: {}",
                duplicates.join(", ")
            );
        }
        Ok(())
    }

    fn check_apply(
        &mut self,
        policy_mode: PolicyMode,
        parameters: &Parameters,
    ) -> CheckApplyResult {
        self.validate(parameters)?;
        let p: ChocoModuleParameters =
            serde_json::from_value(Value::Object(parameters.data.clone()))
                .context("Could not deserialize the module parameters")?;
        self.check_apply_inner(policy_mode, &p)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    #[test]
    fn test_validate_on_duplicates() -> anyhow::Result<()> {
        let m = ChocoModule {
            runner: ChocoRunner::new(),
            state: vec![],
        };
        let err = m
            .validate(&Parameters::new(
                "test-node".to_string(),
                serde_json::from_str(
                    r#"{
            "packages": [
                {"name": "git", "version": "latest", "status": "present"},
                {"name": "Git", "version": "any", "status": "present"}
            ]
        }"#,
                )?,
                PathBuf::from("/tmp"),
            ))
            .unwrap_err();
        assert!(format!("{err:#}").contains("duplicates"));
        Ok(())
    }

    #[test]
    fn test_validate_on_ok_data() -> anyhow::Result<()> {
        let m = ChocoModule {
            runner: ChocoRunner::new(),
            state: vec![],
        };
        m.validate(&Parameters::new(
            "test-node".to_string(),
            serde_json::from_str(
                r#"{
            "packages": [
                {"name": "git", "version": "latest", "status": "present"},
                {"name": "Git-windows", "version": "any", "status": "present"}
            ]
        }"#,
            )?,
            PathBuf::from("/tmp"),
        ))?;
        Ok(())
    }
}
