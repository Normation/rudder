// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2021 Normation SAS

//! Agent-side implementation of base resource_type types

use std::process::exit;

use anyhow::{Error, Result};
use gumdrop::Options;
use serde::{Deserialize, Serialize};

use crate::cfengine::CfengineRunner;
use crate::parameters::Parameters;

pub mod cfengine;
pub mod parameters;
mod specification;

/// Information about the resource type to pass to the library
///
/// These fields are the fields required by the library and need to be
/// implemented by all promise types.
#[derive(Debug, PartialEq, Serialize, Deserialize, Default, Clone)]
pub struct ResourceTypeMetadata {
    name: String,
    version: String,
    description: String,
    /// Markdown formatted documentation
    documentation: Option<String>,
    metadata: Option<String>,
}

impl ResourceTypeMetadata {
    pub fn from_metadata(metadata: &'static str) -> Result<Self> {
        let parsed: Self = serde_yaml::from_str(metadata)?;
        Ok(ResourceTypeMetadata {
            metadata: Some(metadata.to_string()),
            ..parsed
        })
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
    /// Load metadata from default `rudder_resource.yml` file
    fn metadata(&self) -> ResourceTypeMetadata {
        let raw = include_str!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/",
            "rudder_resource.yml"
        ));
        ResourceTypeMetadata::from_metadata(raw).expect("invalid metadata")
    }

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

    /// Run before normal executor termination,
    ///
    /// can be used for clean up tasks.
    fn terminate(&mut self) -> ProtocolResult {
        ProtocolResult::Success
    }
}

#[derive(Debug, PartialEq, Serialize, Deserialize, Clone, Copy)]
pub enum PolicyMode {
    Enforce,
    Audit,
}

impl Default for PolicyMode {
    fn default() -> Self {
        Self::Enforce
    }
}

pub type ValidateResult = Result<()>;

/// We don't map detailed Rudder types here (`compliance_` vs. `result_`, _na, etc.) for two reasons:
///
/// * We want to abstract external concerns as much as possible and make the interface minimal.
///   It is up to the calling agent to map the outcome to the expected semantic.
/// * To match CFEngine's behavior
#[derive(Debug, PartialEq, Clone)]
pub enum Outcome {
    Success(Option<String>),
    Repaired(String),
}

impl Outcome {
    pub fn repaired(message: &str) -> Self {
        Self::Repaired(message.into())
    }

    pub fn success() -> Self {
        Self::Success(None)
    }

    pub fn success_with(message: &str) -> Self {
        Self::Success(Some(message.into()))
    }
}

/// Promise application result
pub type CheckApplyResult = Result<Outcome>;

/// Init/Terminate result
#[derive(Debug, PartialEq, Serialize, Deserialize, Clone)]
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
