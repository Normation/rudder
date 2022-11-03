// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Normation SAS

//! Agent-side implementation of base resource_type types

use std::fmt;
use std::fmt::Write;
use std::process::exit;

use anyhow::{Error, Result};
use gumdrop::Options;
use serde::{Deserialize, Serialize};
use similar::{ChangeTag, TextDiff};

use crate::{cfengine::CfengineRunner, parameters::Parameters};

pub mod cfengine;
pub mod parameters;

/// Information about the resource type to pass to the library
///
/// These fields are the fields required by the library and need to be
/// implemented by all promise types.
#[derive(Debug, PartialEq, Eq, Serialize, Deserialize, Default, Clone)]
pub struct ResourceTypeMetadata {
    name: String,
    version: String,
    description: String,
    /// Markdown formatted documentation
    documentation: Option<String>,
    metadata: Option<String>,
}

impl ResourceTypeMetadata {
    /// Load metadata from yaml content
    pub fn from_metadata(metadata: &'static str) -> Result<Self> {
        let parsed: Self = serde_yaml::from_str(metadata)?;
        Ok(ResourceTypeMetadata {
            metadata: Some(metadata.to_string()),
            ..parsed
        })
    }

    /// Override documentation
    pub fn documentation(self, docs: &'static str) -> Self {
        ResourceTypeMetadata {
            documentation: Some(docs.to_string()),
            ..self
        }
    }
}

/// Rudder resource type
///
/// This is the interface to implement a generic Rudder resource type.
///
/// This library provides adapters to connect it to our agents.
///
/// Protocol versioning will be handled by using different traits.
///
/// ## Metadata
///
/// Each promise type source must come with a metadata file in `YAML` format.
/// It contains the specifications of the resource type inputs and outputs,
/// along with documentation.
///
/// The metadata will be included at compile time to allow distributing a unique file.
///
/// ## Run model
///
/// The resource type for will be started and initialized only once for each agent run.
/// Following requests will be handled sequentially.
///
/// Implementation *must* allow concurrent run of the resource type (or prevent it totally).
///
/// ## Documentation
///
/// The module is able to generate documentation from the given metadata.
pub trait ResourceType0 {
    /// Load metadata from default `rudder_resource_type.yml` and `README.md` files
    fn metadata(&self) -> ResourceTypeMetadata;

    /// Executed before any promise
    ///
    /// Can be used for set-up tasks (connecting to a server, spawning a daemon, etc.)
    fn init(&mut self) -> ProtocolResult {
        ProtocolResult::Success
    }

    /// Checks parameter validity
    ///
    /// Should be used for advanced parameter's validation, additionally to
    /// validation of parameters types.
    fn validate(&self, _parameters: &Parameters) -> ValidateResult {
        Ok(())
    }

    /// Test if the policy is applied and make changes if needed
    ///
    /// Assumes validation has already been done.
    ///
    /// Does not need to be implemented for promises that should be evaluated every time
    /// (usually actions).
    ///
    /// ## Design
    ///
    /// We use a single `checkApply` method instead of separate check and apply to allow simpler
    /// implementing, especially as the two are often very similar.
    ///
    /// ## Parameters
    ///
    /// We pass a generic `serde_json::Value`. This allows the resource type to chose how to treat it,
    /// either parse it completely into structs or leave some generic parts (arbitrary key value, etc.).
    fn check_apply(&mut self, mode: PolicyMode, parameters: &Parameters) -> CheckApplyResult;

    /// Return the current state of the resource in a structured way
    ///
    /// A resource type that does not model a state should return `None`.
    //
    // FIXME: what are the parameters? the subset of parameters that define the resource. Does the output include these?
    fn state(&self, _parameters: &Parameters) -> StateResult {
        Ok(None)
    }

    /// Run before normal executor termination,
    ///
    /// can be used for clean up tasks.
    fn terminate(&mut self) -> ProtocolResult {
        ProtocolResult::Success
    }
}

#[derive(Debug, PartialEq, Eq, Serialize, Deserialize, Clone, Copy)]
#[serde(rename_all = "lowercase")]
pub enum PolicyMode {
    Enforce,
    Audit,
}

impl fmt::Display for PolicyMode {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "{}",
            match self {
                PolicyMode::Enforce => "enforce",
                PolicyMode::Audit => "audit",
            }
        )
    }
}

impl Default for PolicyMode {
    fn default() -> Self {
        Self::Enforce
    }
}

pub type StateResult = Result<Option<serde_json::Value>>;
pub type ValidateResult = Result<()>;

/// We don't map detailed Rudder types here (`compliance_` vs. `result_`, _na, etc.) for two reasons:
///
/// * We want to abstract external concerns as much as possible and make the interface minimal.
///   It is up to the calling agent to map the outcome to the expected semantic.
/// * To match CFEngine's behavior
#[derive(Debug, PartialEq, Eq, Clone)]
pub enum Outcome {
    Success(Option<String>),
    Repaired(String),
}

impl Outcome {
    pub fn repaired<S: Into<String>>(message: S) -> Self {
        Self::Repaired(message.into())
    }

    pub fn success() -> Self {
        Self::Success(None)
    }

    pub fn success_with<S: Into<String>>(message: S) -> Self {
        Self::Success(Some(message.into()))
    }
}

/// Promise application result
pub type CheckApplyResult = Result<Outcome>;

/// Init/Terminate result
#[derive(Debug, PartialEq, Eq, Serialize, Deserialize, Clone)]
pub enum ProtocolResult {
    /// Success
    Success,
    /// Error
    ///
    /// Parameter will be logged at error level
    Failure(String),
    /// Unexpected error
    ///
    /// Parameter will be logged at error level
    Error(String),
}

/// Represents a connector able to run the given resource_type implementation
pub trait Runner0 {
    fn run<T: ResourceType0>(&self, resource_type: T) -> Result<(), Error>;
}

/// Automatically select the right runner for the target platform and run it with
/// default settings.
pub fn run<T: ResourceType0>(resource_type: T) -> Result<(), Error> {
    let cli_cfg = CliConfiguration::parse_args_default_or_exit();

    if cli_cfg.version {
        println!("{} {}", env!("CARGO_PKG_NAME"), env!("CARGO_PKG_VERSION"));
        exit(0)
    } else if cli_cfg.yaml {
        let info = resource_type.metadata();
        if let Some(m) = info.metadata {
            println!("{}", m);
            exit(0)
        } else {
            println!("Missing metadata information");
            exit(1)
        }
    } else if cli_cfg.info {
        let info = resource_type.metadata();
        println!(
            "Rudder resource type: {} v{} (program: {} v{})\n{}",
            info.name,
            info.version,
            env!("CARGO_PKG_NAME"),
            env!("CARGO_PKG_VERSION"),
            info.description
        );
        exit(0)
    }

    #[cfg(target_family = "unix")]
    CfengineRunner::new().run(resource_type)
}

#[derive(Debug, Options)]
// version and description are taken from Cargo.toml
pub struct CliConfiguration {
    #[options(help = "display information about the resource type")]
    pub info: bool,
    #[options(help = "display the resource type specification in yaml format")]
    pub yaml: bool,
    /// Automatically used for help flag
    #[options(help = "print help message")]
    help: bool,
    #[options(help = "print version", short = "V")]
    pub version: bool,
    #[options(help = "verbose", short = "v")]
    pub verbose: bool,
}

#[cfg(feature = "backup")]
pub mod backup {
    //! Helper to produce Rudder-compatible backup files
    //!
    //! The output file name format is taken from our Unix agent.
    //!
    //! Dates are all localtime.

    use std::fmt;
    use std::path::{Path, PathBuf};

    use chrono::prelude::*;

    use rudder_commons::canonify;

    #[derive(Debug, PartialEq, Eq, Clone, Copy)]
    pub enum Backup {
        BeforeEdit,
        Moved,
    }

    impl fmt::Display for Backup {
        fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
            write!(
                f,
                "{}",
                match self {
                    // TODO remove _cf_ prefix?
                    Self::BeforeEdit => "cf_before_edit",
                    Self::Moved => "cf_moved",
                }
            )
        }
    }

    impl Backup {
        pub fn backup_file(self, source: &Path) -> PathBuf {
            let now: DateTime<Utc> = Utc::now();
            self.backup_file_timestamp(source, now.timestamp())
        }

        pub fn backup_file_timestamp(self, source: &Path, timestamp: i64) -> PathBuf {
            let now: DateTime<Utc> = Utc.timestamp(timestamp, 0);
            let file = format!(
                "{}_{}_{}_{}",
                source.to_string_lossy(),
                now.timestamp(),
                now.to_rfc3339(),
                // ctime as used by CFEngine, but it is locale-dependant
                //now.format("%c"),
                self
            );
            PathBuf::from(canonify(&file))
        }
    }

    #[cfg(test)]
    mod tests {
        use pretty_assertions::assert_eq;

        use super::*;

        #[test]
        fn it_generates_backup_file_names() {
            let backup = Backup::BeforeEdit
                .backup_file_timestamp(Path::new("/opt/rudder/etc/relayd/main.conf"), 1653943305);
            // CFEngine format
            //assert_eq!(backup.to_string_lossy(), "_opt_rudder_etc_relayd_main_conf_1653943305_Mon_May_30_22_41_59_2022_cf_before_edit");
            assert_eq!(backup.to_string_lossy(), "_opt_rudder_etc_relayd_main_conf_1653943305_2022_05_30T20_41_45_00_00_cf_before_edit");
        }
    }
}

struct Line(Option<usize>);

impl fmt::Display for Line {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        match self.0 {
            None => write!(f, "    "),
            Some(idx) => write!(f, "{:<4}", idx + 1),
        }
    }
}

// https://github.com/mitsuhiko/similar/blob/main/examples/terminal-inline.rs
pub fn diff(old: &str, new: &str) -> String {
    let mut result = String::new();
    let diff = TextDiff::from_lines(old, new);
    for (idx, group) in diff.grouped_ops(3).iter().enumerate() {
        if idx > 0 {
            write!(result, "{:-^1$}", "-", 80).unwrap();
        }
        for op in group {
            for change in diff.iter_inline_changes(op) {
                let sign = match change.tag() {
                    ChangeTag::Delete => "-",
                    ChangeTag::Insert => "+",
                    ChangeTag::Equal => " ",
                };
                writeln!(
                    result,
                    "{}{} |{}",
                    Line(change.old_index()),
                    Line(change.new_index()),
                    sign,
                )
                .unwrap();
                for (emphasized, value) in change.iter_strings_lossy() {
                    if emphasized {
                        writeln!(result, "{}", value).unwrap();
                    } else {
                        writeln!(result, "{}", value).unwrap();
                    }
                }
                if change.missing_newline() {
                    writeln!(result).unwrap();
                }
            }
        }
    }
    result
}
