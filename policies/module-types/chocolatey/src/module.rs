// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Normation SAS

use anyhow::{Context, Result, bail};
use rudder_cli::spinner::{self, Spinner};
use rudder_module_type::{
    CheckApplyResult, ModuleTypeMetadata, PolicyMode, ProtocolResult, ValidateResult,
    parameters::Parameters,
};
use rudder_module_type::{ModuleType0, Outcome, rudder_info};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections;
use std::{fmt, fs};

use crate::choco::{AvailablePackage, ChocoRunner, InstalledPackage, commands};
use crate::parameters::{
    ChocoModuleParameters, PackageEntry, RudderPackageStatus, RudderPackageVersion,
};

pub const MODULE_NAME: &str = env!("CARGO_PKG_NAME");
pub const MODULE_FEATURES: [&str; 0] = [];

#[derive(Debug, Copy, Clone, PartialEq, Eq)]
pub enum ModuleOutcome {
    Success,
    Repaired,
    NonCompliant,
}

pub struct ModuleReport {
    pub outcome: ModuleOutcome,
    pub message: String,
}

impl From<ModuleReport> for CheckApplyResult {
    fn from(mr: ModuleReport) -> Self {
        match mr.outcome {
            ModuleOutcome::Success => Ok(Outcome::success()),
            ModuleOutcome::Repaired => Ok(Outcome::repaired(mr.message)),
            ModuleOutcome::NonCompliant => bail!(mr.message),
        }
    }
}

/// Result of an audit on a package state
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AuditEntryOutcome {
    Compliant(String),
    NonCompliant(String),
}

/// Result of an action on a package (install|upgrade|uninstall)
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
/// An installed chocolatey package as seen by the module
/// If the package is up-to-date, `latest_available_version` is `None`
#[derive(Debug)]
pub struct PackageState {
    name: String,
    version: String,
    latest_available_version: Option<String>,
    _pinned_version: Option<()>,
}

#[derive(Default, Debug)]
pub struct ChocoModule {
    runner: ChocoRunner,
    state: Vec<PackageState>,
}

// TODO multiple versions installed case
impl ChocoModule {
    /// Gather the currently installed packages info from various `choco` command
    /// execution
    pub fn read_state(&mut self) -> Result<()> {
        let installed = self.runner.run(&commands::ListInstalledPackages)?;
        let outdated = self.runner.run(&commands::ListOutdatedPackages)?;
        self.state = Self::compute_current_state(&installed, &outdated, &[]);
        Ok(())
    }

    /// Compute the [`PackageState`] from a list of installed,
    /// available and pinned packages
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

    /// Returns the current state of a [`PackageEntry`]
    /// Returns `None` if the package is not found installed
    fn get_entry_state(&self, e: &PackageEntry) -> Option<&PackageState> {
        self.state.iter().find(|x| x.name == e.name)
    }

    /// Compare the expected state of a [`PackageEntry`] with
    /// its current [`PackageState`].
    fn audit_entry(&self, e: &PackageEntry) -> AuditEntryOutcome {
        use crate::parameters::RudderPackageStatus::{Absent, Present};
        use crate::parameters::RudderPackageVersion::{Any, Exact, Latest};
        use AuditEntryOutcome::{Compliant, NonCompliant};
        let name = &e.name;
        let expected_version = &e.version;
        let expected_status = &e.status;
        match (&expected_status, &expected_version, self.get_entry_state(e)) {
            (Present, _, None) => NonCompliant(format!(
                "'{}' in version '{:?}' was not found installed",
                name, expected_version
            )),
            (Present, Any, Some(x)) => Compliant(format!(
                "'{}' was found installed in version '{}'",
                name, x.version
            )),
            (Present, Latest, Some(x)) => {
                if let Some(lv) = &x.latest_available_version {
                    NonCompliant(format!(
                        "'{}' was found installed in version '{}' when the latest available is '{}'",
                        name, x.version, lv
                    ))
                } else {
                    Compliant(format!(
                        "'{}' was found installed in version '{}' which is the latest available",
                        name, x.version
                    ))
                }
            }
            (Present, Exact(ev), Some(x)) => {
                if ev == &x.version {
                    Compliant(format!(
                        "'{}' was found installed in version '{}'",
                        name, x.version
                    ))
                } else {
                    NonCompliant(format!(
                        "'{}' was found installed in version '{}' when '{}' was required",
                        name, x.version, ev
                    ))
                }
            }
            (Absent, _, None) => Compliant(format!(
                "'{}' in version '{:?}' was not found installed",
                name, expected_version
            )),
            (Absent, Any, Some(x)) => NonCompliant(format!(
                "'{}' was found installed in version '{}' when it was required to be absent whatever the version",
                name, x.version
            )),
            (Absent, Latest, Some(x)) => {
                if let Some(lv) = &x.latest_available_version {
                    Compliant(format!(
                        "'{}' was found installed in version '{}' when the version '{}' is available, and the latest available version was required to be absent",
                        name, x.version, lv
                    ))
                } else {
                    NonCompliant(format!(
                        "'{}' was found installed in version '{}' which is the latest available, and the latest available version was required to be absent",
                        name, x.version
                    ))
                }
            }
            (Absent, Exact(ev), Some(x)) => {
                if ev == &x.version {
                    NonCompliant(format!(
                        "'{}' was found installed in version '{}' when it was required to be absent",
                        name, x.version
                    ))
                } else {
                    Compliant(format!(
                        "'{}' was found installed in version '{}' when the version '{}' was required to be absent",
                        name, x.version, ev
                    ))
                }
            }
        }
    }

    /// Executes an action to try to enforce a [`PackageEntry`]
    pub fn repair_entry(&self, entry: &PackageEntry) -> Result<EntryOutcome> {
        use crate::parameters::RudderPackageStatus::{Absent, Present};
        let spinner = Spinner::start(format!("Trying to repair package {}...", entry.name));
        let r = match entry.status {
            Absent => {
                let cmd = commands::UninstallPackage::new(&entry.name, entry.version.clone(), entry.extra_params.clone()).context(format!("An unexpected error occurred while building the chocolatey CLI command to uninstall package '{}'.", entry.name))?;
                match self.runner.run(&cmd) {
                    Err(e) => {
                        bail!(
                            "An unexpected error occurred while trying to uninstall the package '{:?}'. \n{:?}",
                            entry,
                            e
                        )
                    }
                    Ok(_) => Ok(EntryOutcome::Repaired(format!(
                        "'{}' was successfully uninstalled",
                        entry.name
                    ))),
                }
            }
            Present => {
                let cmd = commands::InstallPackage::new(&entry.name, entry.version.clone(), entry.extra_params.clone()).context(format!("An unexpected error occurred while building the chocolatey CLI command to install package '{}'.", entry.name))?;
                match self.runner.run(&cmd) {
                    Err(e) => {
                        bail!(
                            "An unexpected error occurred while trying to install the package '{:?}'. \n{:?}",
                            entry,
                            e
                        )
                    }
                    Ok(_) => Ok(EntryOutcome::Repaired(format!(
                        "'{}' was successfully installed",
                        entry.name
                    ))),
                }
            }
        };
        Spinner::stop_with_success(spinner);
        r
    }

    fn verify_repair(&self, entry: &PackageEntry, initial_outcome: EntryOutcome) -> EntryOutcome {
        let audit_post_outcome = self.audit_entry(entry);
        match (initial_outcome, audit_post_outcome) {
            (EntryOutcome::Success(_), AuditEntryOutcome::NonCompliant(after)) => {
                EntryOutcome::Error(format!(
                    "'{}' state was initially correct but its state changed after other packages were changed: {}",
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

    pub fn check_apply_inner(
        &mut self,
        policy_mode: PolicyMode,
        parameters: &ChocoModuleParameters,
    ) -> Result<Vec<EntryOutcome>> {
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
            let spinner = spinner::Spinner::start("Post repair checks".to_string());
            self.read_state()
                .context("Could not list the chocolatey packages installed after the repair")?;
            outcomes = parameters
                .packages
                .iter()
                .zip(outcomes)
                .map(|(entry, outcome)| self.verify_repair(entry, outcome))
                .collect();
            Spinner::stop_with_success(spinner);
        };
        Ok(outcomes)
    }

    pub fn summarize(outcomes: &Vec<EntryOutcome>) -> ModuleReport {
        let mut message = String::new();
        let mut success: Vec<String> = Vec::new();
        let mut repaired: Vec<String> = Vec::new();
        let mut non_compliant: Vec<String> = Vec::new();
        let mut error: Vec<String> = Vec::new();

        for outcome in outcomes {
            match outcome {
                EntryOutcome::Success(s) => success.push(s.clone()),
                EntryOutcome::Repaired(s) => repaired.push(s.clone()),
                EntryOutcome::NonCompliant(s) => non_compliant.push(s.clone()),
                EntryOutcome::Error(s) => error.push(s.clone()),
            }
        }

        if !success.is_empty() {
            message.push_str(&format!("SUCCESS:\n  {}\n", success.join("\n  ")));
        }
        if !non_compliant.is_empty() {
            message.push_str(&format!(
                "NON COMPLIANT:\n  {}\n",
                non_compliant.join("\n  ")
            ));
        }
        if !repaired.is_empty() {
            message.push_str(&format!("REPAIRED:\n  {}\n", repaired.join("\n  ")));
        }
        if !error.is_empty() {
            message.push_str(&format!("ERROR:\n  {}\n", error.join("\n  ")));
        }

        if !non_compliant.is_empty() | !error.is_empty() {
            message.push_str("The target configuration was non compliant");
            ModuleReport {
                outcome: ModuleOutcome::NonCompliant,
                message,
            }
        } else if !repaired.is_empty() {
            message.push_str("The target configuration was repaired");
            ModuleReport {
                outcome: ModuleOutcome::Repaired,
                message,
            }
        } else {
            message.push_str("The target configuration was already compliant");
            ModuleReport {
                outcome: ModuleOutcome::Success,
                message,
            }
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

        // Refuse to manage duplicates
        let mut seen = collections::HashSet::new();
        let duplicates: Vec<&str> = cmp
            .packages
            .iter()
            .map(|e| e.name.as_str())
            .filter(|name| !seen.insert(name.to_owned()))
            .collect();
        if !duplicates.is_empty() {
            bail!(
                "Each package can only be listed once, found duplicates: {}",
                duplicates.join(", ")
            );
        }

        // Refuse to manage 'absent' packages in 'latest' version
        let absent_latest: Vec<PackageEntry> = cmp
            .packages
            .into_iter()
            .filter(|e| {
                e.status == RudderPackageStatus::Absent
                    && matches!(e.version, RudderPackageVersion::Latest)
            })
            .collect();
        if !absent_latest.is_empty() {
            bail!(
                "A package can not be required in 'Absent' status and 'Latest' version: {:?}",
                absent_latest[0]
            )
        }
        Ok(())
    }

    fn check_apply(
        &mut self,
        policy_mode: PolicyMode,
        parameters: &Parameters,
    ) -> CheckApplyResult {
        rudder_info!("Validate");
        self.validate(parameters)?;
        rudder_info!("Parsing parameters to CHocoModuleParameters");
        let p: ChocoModuleParameters =
            serde_json::from_value(Value::Object(parameters.data.clone()))
                .context("Could not deserialize the module parameters")?;
        rudder_info!("Reading state");
        self.read_state()
            .context("Could not list the installed chocolatey packages")?;
        rudder_info!("Running check_apply_inner");
        let outcomes = self.check_apply_inner(policy_mode, &p)?;
        rudder_info!("Summarize");
        let report = Self::summarize(&outcomes);
        if let Some(r) = p.report_file {
            rudder_info!("Writing report");
            fs::write(&r, report.message.clone()).context(format!(
                "Could not write the module report file to '{}'",
                r.display()
            ))?;
        }
        report.into()
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
                {"name": "git", "version": "any", "status": "present"}
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
                {"name": "Git", "version": "any", "status": "present"}
            ]
        }"#,
            )?,
            PathBuf::from("/tmp"),
        ))?;
        Ok(())
    }
}
